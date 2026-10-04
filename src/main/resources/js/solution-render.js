/*
 * Markdown → HTML 渲染，题解正文与洛谷题面正文共用。
 *
 * 两处正文都是**用户提交的 Markdown 方言**（题面里的 `[受信任的用户](https://…)`、
 * 题解里的 `- 列表`），不交给 marked 就原样显示。这里承担三道活：
 * 1. 公式保护：marked 会把 `$a_1 * b_2$` 里的 `_`/`*` 当成语法，故先占位、渲染后还原；
 * 2. 清洗：剥掉脚本、内联事件与危险嵌入，口径与 Kotlin 侧 PreviewPanel.sanitize() 一致；
 * 3. 链接摘 href：见 neutralizeLinks()，免得一点就把面板导航到洛谷。
 *
 * 浏览器里由 PreviewPanel 注入执行；同时导出给 Node 侧做纯函数校验（见 renderMarkdown）。
 */
(function () {
    "use strict";

    var MATH_PLACEHOLDER = /@@LUOGUMATH(\d+)@@/g;
    // 行内式 $...$ 不允许跨行；块级式 $$...$$ 可以跨行，故放在前面优先匹配
    var MATH_DELIMITERS = /\$\$[\s\S]+?\$\$|\$[^$\n]+\$/g;

    function protectMath(markdown) {
        var store = [];
        var text = markdown.replace(MATH_DELIMITERS, function (match) {
            store.push(match);
            return "@@LUOGUMATH" + (store.length - 1) + "@@";
        });
        return { text: text, store: store };
    }

    function restoreMath(html, store) {
        return html.replace(MATH_PLACEHOLDER, function (match, index) {
            return store[Number(index)] || "";
        });
    }

    /*
     * 链接一律**摘掉 href 但留着去处当 title**。
     *
     * marked 会把 `[受信任的用户](https://help.luogu.com.cn/…)` 变成真的 `<a href>`，
     * 而这块正文是装在 IDE 里的 JCEF 面板上的 —— 不摘掉的话一点击就把预览页导航到洛谷官网，
     * 他得重新双击题目才能回来（比原来「链接没渲染」更烦）。
     * 摘掉后文字仍然是强调色、鼠标悬停能看到去处，且与 Kotlin 侧 sanitize() 的口径一致
     * （兜底路径本来就没有可点的链接）。
     */
    function neutralizeLinks(html) {
        return html.replace(/<a\b[^>]*>/gi, function (tag) {
            var url = /\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(tag);
            var dest = url ? (url[1] || url[2] || url[3] || "") : "";
            var stripped = tag.replace(/\s*href\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/i, "");
            if (!dest || /^#/.test(dest)) return stripped;
            // 危险协议连去处都不显示：href 已经摘掉、点不动，但把「javascript:…」挂在
            // 提示上读起来像还能点，不如不提。
            if (/^(javascript|data|vbscript)\s*:/i.test(dest)) return stripped;
            return stripped.replace(/>$/, " title=\"" + dest.replace(/"/g, "&quot;") + "\">");
        });
    }

    function clean(html) {
        return neutralizeLinks(
            html
                .replace(/<script[\s\S]*?<\/script>/gi, "")
                .replace(/<iframe[\s\S]*?<\/iframe>/gi, "")
                .replace(/<object[\s\S]*?<\/object>/gi, "")
                .replace(/<embed[^>]*>/gi, "")
                .replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, "")
        );
    }

    function renderMarkdown(md) {
        if (!window.marked || typeof window.marked.parse !== "function") return "";
        var protectedText = protectMath(md || "");
        var html = window.marked.parse(protectedText.text);
        return restoreMath(clean(html), protectedText.store);
    }

    /** 一段正文：把宿主 div 对应的那份 JSON 里的 markdown 渲进去。返回是否成功。 */
    function renderInto(host, sourceId) {
        var source = document.getElementById(sourceId);
        if (!source) return false;
        var markdown;
        try {
            // 正文以 JSON 字符串字面量内嵌，避免任何闭合标签/引号把脚本截断
            markdown = JSON.parse(source.textContent);
        } catch (e) {
            return false;
        }
        var html = renderMarkdown(markdown);
        if (!html) return false;
        host.innerHTML = html;
        return true;
    }

    function boot() {
        /*
         * 题面：一段一段标在 data-luogu-md 上（背景 / 描述 / 输入格式 / 输出格式 / 提示），
         * 洛谷的正文是他们自己那套 Markdown 方言 —— 不交给 marked，`[文字](链接)` 与
         * `- 列表` 会原样显示（他贴回来的 B2002 就是这样）。
         *
         * 宿主 div 里本来就写着原文（Kotlin 侧把同一份内容放了两处），所以**渲染失败时什么都不做**：
         * 留着原样比写一句「渲染失败」再抹掉正文好。
         */
        var blocks = document.querySelectorAll("[data-luogu-md]");
        if (blocks.length) {
            for (var i = 0; i < blocks.length; i++) {
                renderInto(blocks[i], blocks[i].getAttribute("data-luogu-md"));
            }
            return;
        }
        // 题解沿用原来那对固定 id（DOM 契约不变）
        var host = document.getElementById("luogu-solution");
        if (!host) return;
        renderInto(host, "luogu-md");
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = {
            protectMath: protectMath,
            restoreMath: restoreMath,
            clean: clean,
            neutralizeLinks: neutralizeLinks,
            renderMarkdown: renderMarkdown,
        };
    }
    if (typeof document !== "undefined") boot();
})();
