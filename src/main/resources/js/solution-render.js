/*
 * 题解正文渲染：Markdown → HTML，并保证 LaTeX 公式不被 Markdown 解析器吃掉。
 *
 * 洛谷题解正文是**任意用户提交的 Markdown 源码**，所以这里承担两道活：
 * 1. 公式保护：marked 会把 `$a_1 * b_2$` 里的 `_`/`*` 当成语法，故先占位、渲染后还原；
 * 2. 清洗：剥掉脚本、内联事件与危险嵌入，口径与 Kotlin 侧 PreviewPanel.sanitize() 一致。
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

    function clean(html) {
        return html
            .replace(/<script[\s\S]*?<\/script>/gi, "")
            .replace(/<iframe[\s\S]*?<\/iframe>/gi, "")
            .replace(/<object[\s\S]*?<\/object>/gi, "")
            .replace(/<embed[^>]*>/gi, "")
            .replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, "")
            .replace(/href\s*=\s*(["'])\s*javascript:[^"']*\1/gi, "href=$1#$1");
    }

    function renderMarkdown(md) {
        if (!window.marked || typeof window.marked.parse !== "function") return "";
        var protectedText = protectMath(md || "");
        var html = window.marked.parse(protectedText.text);
        return restoreMath(clean(html), protectedText.store);
    }

    function boot() {
        var host = document.getElementById("luogu-solution");
        var source = document.getElementById("luogu-md");
        if (!host || !source) return;
        var markdown;
        try {
            // 正文以 JSON 字符串字面量内嵌，避免任何闭合标签/引号把脚本截断
            markdown = JSON.parse(source.textContent);
        } catch (e) {
            return;
        }
        var html = renderMarkdown(markdown);
        if (!html) {
            host.textContent = "题解渲染失败";
            return;
        }
        host.innerHTML = html;
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = {
            protectMath: protectMath,
            restoreMath: restoreMath,
            clean: clean,
            renderMarkdown: renderMarkdown,
        };
    }
    if (typeof document !== "undefined") boot();
})();
