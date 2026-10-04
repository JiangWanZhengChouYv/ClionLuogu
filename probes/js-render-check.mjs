/*
 * 浏览器侧正文渲染的离线断言（marked + 洛谷正文适配）。
 *
 * 为什么单开一份而不是并进 CoreProbe：被检的 `resources/js/solution-render.js` 跑在 JCEF 里，
 * JVM 探针碰不到它；而这个文件要把真的 marked.min.js 载进来跑真的 parse，
 * 否则「`[文字](链接)` 到底渲染成了什么」只能靠打开 IDE 猜。
 *
 * 跑法（本机 PATH 里没有 node 时用 Qoder 自带的 node-repl）：
 *   node probes/js-render-check.mjs
 *   # 或在 node-repl 里：await import('<仓库绝对路径>/probes/js-render-check.mjs')
 * 任一断言失败就抛错（不静默跳过 —— 1.8.0 那两次「假绿」都是跳过没吭声造成的）。
 */
import fs from "node:fs";
import vm from "node:vm";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const resources = path.join(here, "..", "src", "main", "resources");

/** marked 是 UMD，得在独立上下文里拿到 `window.marked`；渲染脚本用 `module.exports` 导出纯函数。 */
function loadRenderer() {
    const box = { window: {}, console, module: { exports: {} } };
    box.self = box.window;
    vm.createContext(box);
    vm.runInContext(fs.readFileSync(path.join(resources, "marked", "marked.min.js"), "utf8"), box);
    vm.runInContext('window.marked = (typeof marked !== "undefined" && marked) || window.marked || null;', box);
    vm.runInContext(fs.readFileSync(path.join(resources, "js", "solution-render.js"), "utf8"), box);
    if (typeof box.window.marked?.parse !== "function") throw new Error("marked 没能载入，下面的断言全部无意义");
    if (typeof box.module.exports.renderMarkdown !== "function") throw new Error("solution-render.js 没导出 renderMarkdown");
    return box.module.exports;
}

const api = loadRenderer();
let checks = 0;
let failed = 0;

function ok(name, condition, actual) {
    checks++;
    if (condition) return;
    failed++;
    console.log("FAIL " + name + "\n     实际：" + String(actual).replace(/\s+/g, " ").slice(0, 240));
}

// 他从 B2002 复制出来的原样文本：链接没渲染、正文像一行流水账
const link = api.renderMarkdown("本题只给[受信任的用户](https://help.luogu.com.cn/kb/trusted-user)展示。");
ok("Markdown 链接渲染成 <a>", /<a\b/.test(link), link);
ok("链接摘掉 href（不把面板导航到洛谷）", !/href/i.test(link), link);
ok("去处留在 title 里", link.includes('title="https://help.luogu.com.cn/kb/trusted-user"'), link);
ok("链接文字照样可读", link.includes("受信任的用户"), link);

const list = api.renderMarkdown("- 第一行\n- 第二行");
ok("短横线列表渲染成 ul/li", /<ul>[\s\S]*<li>第一行<\/li>/.test(list), list);

const bold = api.renderMarkdown("**时间限制**: 1000 ms");
ok("粗体元信息渲染", bold.includes("<strong>时间限制</strong>"), bold);

// 公式：marked 会把 `_`/`*` 当 Markdown 语法，占位保护必须让它们原样交给 MathJax
const inline = api.renderMarkdown("求 $a_1 * b_2$ 的值。");
ok("行内公式原样交给 MathJax", inline.includes("$a_1 * b_2$"), inline);
ok("公式里没长出 <em>/<strong>", !/<em>|<strong>/.test(inline), inline);

const display = api.renderMarkdown("$$\\frac{1}{2}$$");
ok("块级公式原样交给 MathJax", display.includes("$$\\frac{1}{2}$$"), display);

const evil = api.renderMarkdown("<script>alert(1)</script>正文<img src=x onerror=alert(1)>");
ok("脚本被剥掉", !/script/i.test(evil), evil);
ok("内联事件被剥掉", !/onerror/i.test(evil), evil);

// 危险协议：href 已经没了，连去处都不该显示出来
const jsUrl = api.clean('<a href="javascript:alert(1)">点</a>');
ok("javascript: 链接既不可点也不显示去处", !/javascript/i.test(jsUrl), jsUrl);

const quoted = api.neutralizeLinks('<a class="x" href="https://a.test/x">文</a>');
ok("普通外链换成「不可点但看得见去处」", /^<a class="x" title="https:\/\/a\.test\/x">文<\/a>$/.test(quoted), quoted);

const marker = api.neutralizeLinks('<a href="#anchor">锚</a>');
ok("页内锚点同样摘掉 href", !/href/i.test(marker), marker);

console.log("JS 正文渲染探针：检查 " + checks + " 条，失败 " + failed + " 条");
if (failed > 0) throw new Error("JS 正文渲染探针有 " + failed + " 条失败");
