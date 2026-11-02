package com.githubupdater.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时信息：{@code data/runtime.json}。
 *
 * <p>这是发现端口的<b>唯一真相源</b>。端口由系统分配（绑 0），每次启动不同，
 * 所以必须落盘，否则用户关掉日志窗口后就再也找不到面板地址了。
 *
 * <p><b>刻意不写入 token</b>：token 是访问本地 API 的唯一凭证，写进磁盘上的明文文件
 * 等于让任何能读该目录的进程都能调用 API。托盘在同一进程内，直接持有 token，
 * 不需要从文件读。文件里只放 pid / port / url / 版本号这些非敏感信息。
 */
public final class RuntimeInfo {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInfo.class);

    private static final String FILE_NAME = "runtime.json";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public RuntimeInfo(Path dataDir) {
        this.file = dataDir.resolve(FILE_NAME);
    }

    /** 写入运行时信息。启动成功后调用。 */
    public void write(int port, String version, Path dataDir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", ProcessHandle.current().pid());
        m.put("port", port);
        m.put("url", "http://127.0.0.1:" + port);
        m.put("version", version);
        m.put("dataDir", dataDir.toAbsolutePath().toString());
        m.put("startedAt", System.currentTimeMillis());
        try {
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m),
                    StandardCharsets.UTF_8);
            log.debug("已写入运行时信息：{}", file);
        } catch (IOException e) {
            // 写不进去不影响主流程——日志里也有地址
            log.warn("写入 runtime.json 失败：{}", e.getMessage());
        }
    }

    /** 删除运行时信息。正常退出时调用。 */
    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("删除 runtime.json 失败：{}", e.getMessage());
        }
    }

    /**
     * 读取已存在的运行时信息。
     *
     * <p>用于判断"是否已有实例在运行"：文件存在但 pid 已死，说明上次是异常退出，
     * 可以安全覆盖。
     */
    public Optional<Map<?, ?>> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(mapper.readValue(json, Map.class));
        } catch (IOException e) {
            log.debug("读取 runtime.json 失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 检测是否已有存活实例。
     *
     * @return 存活实例的 URL；没有则返回 empty
     */
    public Optional<String> findLiveInstance() {
        return read()
                .filter(m -> m.get("pid") instanceof Number)
                .filter(m -> {
                    long pid = ((Number) m.get("pid")).longValue();
                    // ProcessHandle.of 对不存在的 pid 返回 empty
                    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                })
                .filter(m -> m.get("pid") instanceof Number
                        && ((Number) m.get("pid")).longValue() != ProcessHandle.current().pid())
                .map(m -> String.valueOf(m.get("url")));
    }

    public Path file() {
        return file;
    }
}
