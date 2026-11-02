package com.githubupdater.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 资产匹配器。
 *
 * <p>用 glob 通配符而非正则：规则由界面从资产清单勾选后自动生成，
 * 用户看到的是 {@code *-win-x64.zip} 这种可读形式。
 *
 * <p><b>匹配到多个资产时必须报错让用户选，不自动挑</b>——挑错一个 arm64
 * 装到 x64 上是这类工具最容易造成的实际损害。
 */
public final class AssetMatcher {

    private AssetMatcher() {
    }

    /**
     * 在给定资产清单中匹配。
     *
     * @param assets      候选资产名
     * @param includeGlob 包含规则，必填
     * @param excludeGlob 排除规则，可为 null；<b>排除优先于包含</b>
     * @return 匹配结果
     */
    public static Result match(List<String> assets, String includeGlob, String excludeGlob) {
        if (assets == null || assets.isEmpty()) {
            return Result.none();
        }
        if (includeGlob == null || includeGlob.isBlank()) {
            throw new IllegalArgumentException("包含规则不能为空");
        }

        Pattern include = compileGlob(includeGlob);
        Pattern exclude = (excludeGlob == null || excludeGlob.isBlank())
                ? null
                : compileGlob(excludeGlob);

        List<String> matched = new ArrayList<>();
        for (String asset : assets) {
            if (asset == null) {
                continue;
            }
            if (exclude != null && exclude.matcher(asset).matches()) {
                continue;   // 排除优先
            }
            if (include.matcher(asset).matches()) {
                matched.add(asset);
            }
        }

        return switch (matched.size()) {
            case 0 -> Result.none();
            case 1 -> Result.single(matched.get(0));
            default -> Result.ambiguous(matched);
        };
    }

    /**
     * 把 glob 转成正则。
     *
     * <p>支持 {@code *}（任意字符序列）与 {@code ?}（单个字符），其余字符按字面量处理。
     * 不区分大小写——GitHub 上的资产名大小写并不统一，用户不该为此踩坑。
     */
    static Pattern compileGlob(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                // 正则元字符全部转义，避免 *.zip 里的 . 变成"任意字符"
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** 匹配结果。三种情形必须区分对待。 */
    public record Result(Kind kind, List<String> matched, String message) {

        public enum Kind {
            /** 未匹配到任何资产。 */
            NONE,
            /** 恰好匹配一个，可以继续。 */
            SINGLE,
            /** 匹配到多个，必须让用户选择。 */
            AMBIGUOUS
        }

        static Result none() {
            return new Result(Kind.NONE, List.of(), "未匹配到任何资产");
        }

        static Result single(String asset) {
            return new Result(Kind.SINGLE, List.of(asset), null);
        }

        static Result ambiguous(List<String> assets) {
            return new Result(Kind.AMBIGUOUS, List.copyOf(assets),
                    "匹配到 " + assets.size() + " 个资产，无法确定用哪个");
        }

        public boolean isSingle() {
            return kind == Kind.SINGLE;
        }

        public String singleAsset() {
            if (kind != Kind.SINGLE) {
                throw new IllegalStateException("不是单匹配结果");
            }
            return matched.get(0);
        }
    }
}
