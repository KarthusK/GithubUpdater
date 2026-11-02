package com.githubupdater.infra.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Restart Manager 封装：检测文件被哪些进程占用。
 *
 * <p><b>为什么用 Restart Manager 而不是枚举句柄</b>：它只用公开 Win32 API，
 * 无需驱动、无需管理员权限，比 {@code NtQuerySystemInformation} 句柄枚举
 * （handle.exe 那类做法）稳健得多——后者依赖未文档化结构，随版本漂移。
 *
 * <p><b>关键陷阱</b>：只注册<b>文件</b>，绝不注册目录。
 * 注册目录会让 {@code RmGetList} 返回 {@code ERROR_ACCESS_DENIED(5)}，
 * 而官方对该错误码的定义是 "A path registered to the session is a directory"。
 * 网上大量"返回 5 是权限不足"的说法是误读。
 */
public final class RestartManager {

    private static final Logger log = LoggerFactory.getLogger(RestartManager.class);

    /** ERROR_MORE_DATA：需要更大的缓冲区，属正常流程。 */
    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_MORE_DATA = 234;

    /** RmProcessInfo.ApplicationType 的取值。 */
    private static final int CCH_RM_MAX_APP_NAME = 255;
    private static final int CCH_RM_MAX_SVC_NAME = 63;

    private static final boolean AVAILABLE = probeAvailability();

    private RestartManager() {
    }

    /** Restart Manager 是否可用（非 Windows 平台或调用失败时为 false）。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    private static boolean probeAvailability() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("win")) {
                return false;
            }
            // 尝试加载 rstrtmgr，失败说明系统不支持
            Native.load("rstrtmgr", RstrtmgrLibrary.class);
            return true;
        } catch (Throwable t) {
            log.debug("Restart Manager 不可用：{}", t.getMessage());
            return false;
        }
    }

    /**
     * 查询占用指定文件的进程。
     *
     * @param filePath 目标文件的绝对路径
     * @return 占用进程列表；无法查询时返回空列表（调用方应保守处理）
     */
    public static List<LockingProcess> findLockingProcesses(String filePath) {
        if (!AVAILABLE) {
            return List.of();
        }
        if (filePath == null || filePath.isBlank()) {
            return List.of();
        }

        RstrtmgrLibrary lib;
        try {
            lib = Native.load("rstrtmgr", RstrtmgrLibrary.class);
        } catch (Throwable t) {
            log.debug("加载 rstrtmgr 失败：{}", t.getMessage());
            return List.of();
        }

        int session = -1;
        try {
            IntByReference sessionHandle = new IntByReference();
            char[] sessionKey = new char[256];
            int rc = lib.RmStartSession(sessionHandle, 0, sessionKey);
            if (rc != ERROR_SUCCESS) {
                log.debug("RmStartSession 失败，返回 {}", rc);
                return List.of();
            }
            session = sessionHandle.getValue();

            // 只注册文件路径。若误传目录，RmGetList 会返回 5（ERROR_ACCESS_DENIED），
            // 这不是权限问题，而是"注册了目录"。
            WString[] resources = {new WString(filePath)};
            rc = lib.RmRegisterResources(session, resources.length, resources, 0, null, 0, null);
            if (rc != ERROR_SUCCESS) {
                log.debug("RmRegisterResources 失败，返回 {}（路径：{}）", rc, filePath);
                return List.of();
            }

            // 第一次调用取所需缓冲区大小
            IntByReference pnProcInfoNeeded = new IntByReference();
            IntByReference pnProcInfo = new IntByReference(0);
            IntByReference lpdwRebootReasons = new IntByReference();

            rc = lib.RmGetList(session, pnProcInfoNeeded, pnProcInfo, null, lpdwRebootReasons);
            if (rc == ERROR_SUCCESS) {
                return List.of();      // 没有进程占用
            }
            if (rc != ERROR_MORE_DATA) {
                log.debug("RmGetList 首次调用返回 {}", rc);
                return List.of();
            }

            int needed = pnProcInfoNeeded.getValue();
            if (needed <= 0) {
                return List.of();
            }

            // 分配数组重取
            RmProcessInfo[] infos = new RmProcessInfo[needed];
            pnProcInfo.setValue(needed);
            rc = lib.RmGetList(session, pnProcInfoNeeded, pnProcInfo, infos, lpdwRebootReasons);
            if (rc != ERROR_SUCCESS) {
                log.debug("RmGetList 二次调用返回 {}", rc);
                return List.of();
            }

            List<LockingProcess> result = new ArrayList<>();
            int count = Math.min(pnProcInfo.getValue(), needed);
            for (int i = 0; i < count; i++) {
                RmProcessInfo info = infos[i];
                result.add(new LockingProcess(
                        info.Process.dwProcessId,
                        readFixedString(info.strAppName, CCH_RM_MAX_APP_NAME),
                        info.ApplicationType
                ));
            }
            return result;

        } catch (Throwable t) {
            log.warn("查询文件占用失败：{}", t.getMessage());
            return List.of();
        } finally {
            if (session >= 0) {
                try {
                    lib.RmEndSession(session);
                } catch (Throwable ignored) {
                    // 清理失败不影响结果
                }
            }
        }
    }

    /** 读取 C 固定长度字符串（去掉尾部的 NUL）。 */
    private static String readFixedString(char[] chars, int maxLen) {
        if (chars == null) {
            return "";
        }
        int end = 0;
        while (end < chars.length && end < maxLen && chars[end] != 0) {
            end++;
        }
        return new String(chars, 0, end).trim();
    }

    /** 占用文件的进程。 */
    public record LockingProcess(int pid, String name, int applicationType) {

        /** 是否是可执行程序（而非服务）。 */
        public boolean isExecutable() {
            // 0 = RmUnknownApp, 1 = RmMainWindow, 2 = RmOtherWindow,
            // 3 = RmService, 4 = RmExplorer, 5 = RmConsole, 100 = RmCritical
            return applicationType != 3;
        }

        public String describe() {
            return name + " (PID " + pid + ")";
        }
    }

    /** JNA 到 rstrtmgr.dll 的绑定。 */
    private interface RstrtmgrLibrary extends com.sun.jna.Library {
        int RmStartSession(IntByReference pSessionHandle, int dwSessionFlags, char[] strSessionKey);

        int RmRegisterResources(int dwSessionHandle, int nFiles, WString[] rgsFilenames,
                                int nApplications, Pointer[] rgApplications,
                                int nServices, WString[] rgsServiceNames);

        int RmGetList(int dwSessionHandle, IntByReference pnProcInfoNeeded,
                      IntByReference pnProcInfo, RmProcessInfo[] rgAffectedApps,
                      IntByReference lpdwRebootReasons);

        int RmEndSession(int dwSessionHandle);
    }

    /** 对应 RM_PROCESS_INFO 结构。字段顺序与大小必须严格匹配，否则读到垃圾数据。 */
    @com.sun.jna.Structure.FieldOrder({"Process", "strAppName", "strServiceShortName",
            "ApplicationType", "AppStatus", "TSSessionId", "bRestartable"})
    public static class RmProcessInfo extends com.sun.jna.Structure {
        public RmUniqueProcess Process = new RmUniqueProcess();
        public char[] strAppName = new char[CCH_RM_MAX_APP_NAME + 1];
        public char[] strServiceShortName = new char[CCH_RM_MAX_SVC_NAME + 1];
        public int ApplicationType;
        public int AppStatus;
        public int TSSessionId;
        public int bRestartable;

        public RmProcessInfo() {
            super();
        }
    }

    /** 对应 RM_UNIQUE_PROCESS 结构。 */
    @com.sun.jna.Structure.FieldOrder({"dwProcessId", "ProcessStartTime"})
    public static class RmUniqueProcess extends com.sun.jna.Structure {
        public int dwProcessId;
        public com.sun.jna.platform.win32.WinBase.FILETIME ProcessStartTime =
                new com.sun.jna.platform.win32.WinBase.FILETIME();

        public RmUniqueProcess() {
            super();
        }
    }
}
