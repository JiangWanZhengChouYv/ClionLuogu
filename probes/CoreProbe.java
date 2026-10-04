import com.user.clionluogu.service.CompileErrorLocator;
import com.user.clionluogu.service.CompilerService;
import com.user.clionluogu.service.CompareTarget;
import com.user.clionluogu.service.JumpMiss;
import com.user.clionluogu.service.JumpSource;
import com.user.clionluogu.service.ProcessRunner;
import com.user.clionluogu.service.SampleCompareService;
import com.user.clionluogu.service.LocalRunSignature;
import com.user.clionluogu.service.ResourceMeter;
import com.user.clionluogu.service.SampleDiff;
import com.user.clionluogu.service.SampleSetService;
import com.user.clionluogu.service.SelfTestService;
import com.user.clionluogu.ui.BlockText;
import com.user.clionluogu.ui.CodeOrigin;
import com.user.clionluogu.ui.DetailPanel;
import com.user.clionluogu.ui.DetailSection;
import com.user.clionluogu.ui.SampleComparePanel;
import com.user.clionluogu.ui.SelfTestPanel;
import com.user.clionluogu.ui.StatusRow;
import com.user.clionluogu.ui.SubmitPanel;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 纯逻辑断言：不需要 Project、不需要显示器，跑的是「判据本身」。
 *
 * 1.8.0 的重点在两处搬迁：进程内核（ProcessRunner）与「跑不起来」的闸门（LocalRunGates）。
 * 两边都必须和搬迁前**逐字一致**，所以这里的期望值不是随手写的，是从旧代码抄下来的原文。
 */
public class CoreProbe {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok, String actual) {
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name + " 实际=[" + actual + "]");
        }
    }

    // ---- 造数据的小工具 ----

    static SampleSetService.Found found(boolean dirExists, List<SampleSetService.Sample> samples, List<Integer> orphans) {
        return new SampleSetService.Found(dirExists, "/base/P1001_samples", samples, orphans);
    }

    static SampleSetService.Sample sample(int n) {
        return new SampleSetService.Sample(n, new File("/base/P1001_samples/P1001_" + n + ".in"),
            new File("/base/P1001_samples/P1001_" + n + ".out"));
    }

    static CompilerService.Compiler compiler() {
        return new CompilerService.Compiler(new File("/usr/bin/clang++"), "Apple clang version 17");
    }

    static CompareTarget target(SampleSetService.Found f, String source, CompilerService.Compiler c) {
        return new CompareTarget("/base", f, source, c, null,
            new com.user.clionluogu.service.ProblemLimits.Limits(null, null), null);
    }

    static ProcessRunner.Outcome run(String stdout, String stderr, Integer exit, long ms,
                                     boolean timedOut, boolean overLimit, boolean cancelled, String startError) {
        return new ProcessRunner.Outcome(stdout, stderr, exit, ms, timedOut, overLimit, cancelled, startError, null, null);
    }

    static CompilerService.Outcome compile(boolean ok, String diagnostic) {
        return new CompilerService.Outcome(ok, ok ? 0 : 1, diagnostic, 25L, "clang++ ...", false, false);
    }

    static String firstBlockShort(SampleComparePanel.Block b) {
        return b == null ? "<null>" : b.getShort();
    }

    static DetailSection section(List<DetailSection> list, String title) {
        for (DetailSection s : list) if (title.equals(s.getTitle())) return s;
        return null;
    }

    static String row(List<DetailSection> list, String sectionTitle, String key) {
        DetailSection s = section(list, sectionTitle);
        if (s == null) return "<没有这节>";
        for (kotlin.Pair<String, String> p : s.getRows()) if (key.equals(p.getFirst())) return p.getSecond();
        return "<没有这行>";
    }

    public static void main(String[] args) {
        gatesCompare();
        gatesSelfTest();
        pidFollow();
        summaryPriority();
        stdoutBlock();
        naming();
        sections();
        exitCodeHints();
        oldGuards();
        inputPersistence();
        limitsParsing();
        signatureStability();
        meterParsing();
        statusRowRule();

        System.out.println("=== 探针合计：" + pass + " 通过 / " + fail + " 失败 ===");
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
    }

    // ---- 自测输入的持久化：字段漏镜像 = 重启后那份输入没了（1.7.2 踩过同款） ----

    static void inputPersistence() {
        check("存储键去空格并大写",
            com.user.clionluogu.storage.SelfTestInputService.normalizedKey(" p1001 ").equals("P1001"),
            com.user.clionluogu.storage.SelfTestInputService.normalizedKey(" p1001 "));
        check("没存过返回空串而不是 null",
            com.user.clionluogu.storage.SelfTestInputService.textOf(new ArrayList<>(), "P1001").isEmpty(), "null?");

        List<com.user.clionluogu.storage.SelfTestInputService.Entry> list = new ArrayList<>();
        list = com.user.clionluogu.storage.SelfTestInputService.putText(list, "P1001", "3\n4 5\n");
        check("存进去取得回来",
            com.user.clionluogu.storage.SelfTestInputService.textOf(list, "P1001").equals("3\n4 5\n"),
            com.user.clionluogu.storage.SelfTestInputService.textOf(list, "P1001"));
        list = com.user.clionluogu.storage.SelfTestInputService.putText(list, "p1002", "x");
        check("换题号不覆盖别人（也不串内容）",
            com.user.clionluogu.storage.SelfTestInputService.textOf(list, "P1001").equals("3\n4 5\n")
                && com.user.clionluogu.storage.SelfTestInputService.textOf(list, "P1002").equals("x"),
            String.valueOf(list.size()));
        list = com.user.clionluogu.storage.SelfTestInputService.putText(list, "P1001", "   ");
        check("空白就是删条目，不留空壳",
            list.size() == 1 && com.user.clionluogu.storage.SelfTestInputService.textOf(list, "P1001").isEmpty(),
            String.valueOf(list.size()));

        // 整个服务走一遍：存 → getState → 新实例 loadState → 取回
        com.user.clionluogu.storage.SelfTestInputService a = new com.user.clionluogu.storage.SelfTestInputService();
        a.save("P1001", "first\n");
        com.user.clionluogu.storage.SelfTestInputService.State state = a.getState();
        com.user.clionluogu.storage.SelfTestInputService b = new com.user.clionluogu.storage.SelfTestInputService();
        b.loadState(state);
        check("round-trip 后内容一致", b.load("P1001").equals("first\n"), b.load("P1001"));
        // 深拷贝没做的话，改 state 会连着改 a 的内部列表
        state.getEntries().get(0).setText("被改过了");
        check("getState 是深拷贝（改副本不影响原实例）", a.load("P1001").equals("first\n"), a.load("P1001"));
    }

    // ---- 对拍闸门：搬迁后必须逐字一致 ----

    static void gatesCompare() {
        List<SampleSetService.Sample> one = Collections.singletonList(sample(1));

        SampleComparePanel.Block b1 = SampleComparePanel.blockingReason("P1001", target(null, "/base/P1001.cpp", compiler()), null);
        check("gates 项目没落盘", "当前项目没有落盘目录".equals(firstBlockShort(b1)), firstBlockShort(b1));

        SampleComparePanel.Block b2 = SampleComparePanel.blockingReason("P1001",
            target(found(false, Collections.emptyList(), Collections.emptyList()), "/base/P1001.cpp", compiler()), null);
        check("gates 没有样例目录", "没有样例目录".equals(firstBlockShort(b2)), firstBlockShort(b2));

        SampleComparePanel.Block b3 = SampleComparePanel.blockingReason("P1001",
            target(found(true, Collections.emptyList(), Arrays.asList(2, 5)), "/base/P1001.cpp", compiler()), null);
        check("gates 没有成对样例", "样例目录里没有成对的 .in/.out".equals(firstBlockShort(b3)), firstBlockShort(b3));
        check("gates 孤儿 .in 要列编号", b3 != null && b3.getDetail().contains("只有 .in 的编号：2, 5"), b3.getDetail());

        SampleComparePanel.Block b4 = SampleComparePanel.blockingReason("P1001",
            target(found(true, one, Collections.emptyList()), null, compiler()), "/other");
        check("gates 没有源文件", "项目根没有 P1001.cpp".equals(firstBlockShort(b4)), firstBlockShort(b4));
        check("gates 源文件那句话说清楚找的是哪", b4 != null && b4.getDetail().contains("/base/P1001.cpp"), b4.getDetail());

        SampleComparePanel.Block b5 = SampleComparePanel.blockingReason("P1001",
            target(found(true, one, Collections.emptyList()), "/base/P1001.cpp", null), null);
        check("gates 没有编译器", "没找到编译器".equals(firstBlockShort(b5)), firstBlockShort(b5));
        check("gates 找编译器顺序写在 tooltip 里",
            b5 != null && b5.getDetail().contains("PATH 上的 clang++ / g++ / c++"), b5.getDetail());

        check("gates 条件全齐 → 可以开始",
            SampleComparePanel.blockingReason("P1001",
                target(found(true, one, Collections.emptyList()), "/base/P1001.cpp", compiler()), null) == null,
            "非 null");
    }

    // ---- 自测闸门：同一批文案，但**样例那一关必须没有** ----

    static void gatesSelfTest() {
        List<SampleSetService.Sample> one = Collections.singletonList(sample(1));

        // 这条是 1.8.0 的立身之本：没有样例目录也照样能自测
        check("自测 没有样例目录不拦（这正是它存在的理由）",
            SelfTestPanel.blockingReason("P1001",
                target(found(false, Collections.emptyList(), Collections.emptyList()), "/base/P1001.cpp", compiler()), null) == null,
            "被拦了");
        check("自测 样例目录存在但空也不拦",
            SelfTestPanel.blockingReason("P1001",
                target(found(true, Collections.emptyList(), Collections.emptyList()), "/base/P1001.cpp", compiler()), null) == null,
            "被拦了");
        check("自测 项目没落盘仍要拦",
            "当前项目没有落盘目录".equals(firstBlockShort(SelfTestPanel.blockingReason("P1001",
                target(null, "/base/P1001.cpp", compiler()), null))), "没拦住");
        check("自测 没有源文件要拦",
            "项目根没有 P1001.cpp".equals(firstBlockShort(SelfTestPanel.blockingReason("P1001",
                target(found(true, one, Collections.emptyList()), null, compiler()), null))), "拦错条");
        check("自测 没有编译器要拦",
            "没找到编译器".equals(firstBlockShort(SelfTestPanel.blockingReason("P1001",
                target(found(true, one, Collections.emptyList()), "/base/P1001.cpp", null), null))), "拦错条");
    }

    static void pidFollow() {
        check("followsEditor 空框可以跟", SelfTestPanel.followsEditor("", "P1001"), "false");
        check("followsEditor 上次自动填的可以跟", SelfTestPanel.followsEditor("P1001", "P1001"), "false");
        check("followsEditor 手输的不跟", !SelfTestPanel.followsEditor("P1002", "P1001"), "true");

        // 诊断属于跑那次那道题：题号切走了还照跳，跳的是另一道题的同一行号
        check("jump 没跑过不给跳", SelfTestPanel.jumpBlockReason(null, "P1001", true) != null, "给了跳");
        check("jump 没有诊断文本不给跳", SelfTestPanel.jumpBlockReason("P1001", "P1001", false) != null, "给了跳");
        check("jump 题号切走必须说清楚诊断是谁的",
            SelfTestPanel.jumpBlockReason("P1001", "P1002", true).contains("P1001"),
            SelfTestPanel.jumpBlockReason("P1001", "P1002", true));
        check("jump 题号没变、有诊断 → 可以跳",
            SelfTestPanel.jumpBlockReason("P1001", "P1001", true) == null, "被拦了");
    }

    // ---- summaryText 的优先级：谁先谁后是有原因的，不是随手 if ----

    static void summaryPriority() {
        check("summary 超限优先于超时",
            SelfTestService.summaryText(run("x", "", 1, 5_000L, true, true, false, null), 5000).startsWith("输出超过"),
            SelfTestService.summaryText(run("x", "", 1, 5_000L, true, true, false, null), 5000));
        check("summary 超时",
            SelfTestService.summaryText(run("", "", null, 5_000L, true, false, false, null), 5000).contains("超过 5000 ms"),
            SelfTestService.summaryText(run("", "", null, 5_000L, true, false, false, null), 5000));
        check("summary 取消",
            SelfTestService.summaryText(run("", "", 143, 12L, false, false, true, null), 5000).equals("已取消"),
            SelfTestService.summaryText(run("", "", 143, 12L, false, false, true, null), 5000));
        check("summary 没有退出码",
            SelfTestService.summaryText(run("", "", null, 12L, false, false, false, null), 5000).contains("未正常退出"),
            SelfTestService.summaryText(run("", "", null, 12L, false, false, false, null), 5000));
        check("summary 段错误",
            SelfTestService.summaryText(run("", "", 139, 12L, false, false, false, null), 5000).contains("SIGSEGV"),
            SelfTestService.summaryText(run("", "", 139, 12L, false, false, false, null), 5000));
        check("summary 正常",
            SelfTestService.summaryText(run("1 2\n", "", 0, 12L, false, false, false, null), 5000)
                .equals("正常结束 · 退出码 0 · 用时 12 ms"),
            SelfTestService.summaryText(run("1 2\n", "", 0, 12L, false, false, false, null), 5000));
        check("summary 起进程失败最优先（连输出都不必提）",
            SelfTestService.summaryText(run("忽略我", "", 0, 1L, true, true, true, "Too many open files"), 5000)
                .startsWith("进程未能启动"),
            SelfTestService.summaryText(run("忽略我", "", 0, 1L, true, true, true, "Too many open files"), 5000));
        check("自测超时与对拍同一档",
            SelfTestService.DEFAULT_TIMEOUT_MS == SampleCompareService.DEFAULT_TIMEOUT_MS,
            String.valueOf(SelfTestService.DEFAULT_TIMEOUT_MS));
    }

    static void stdoutBlock() {
        check("空 stdout 要说「没有输出」",
            SelfTestService.stdoutBlock(run("", "", 0, 1L, false, false, false, null)).equals("（程序没有输出）"),
            SelfTestService.stdoutBlock(run("", "", 0, 1L, false, false, false, null)));
        check("有输出就原样",
            SelfTestService.stdoutBlock(run("3\n", "", 0, 1L, false, false, false, null)).equals("3\n"),
            SelfTestService.stdoutBlock(run("3\n", "", 0, 1L, false, false, false, null)));
    }

    static void naming() {
        kotlin.Pair<File, File> p = SelfTestService.paths("P1001", new File("/tmp/build"));
        check("自测的 stdin 文件名跟着题号", p.getSecond().getName().equals("P1001.in"), p.getSecond().getName());
        check("自测产物名 = CompilerService.executableName",
            p.getFirst().getName().equals(CompilerService.executableName("P1001")),
            p.getFirst().getName() + " vs " + CompilerService.executableName("P1001"));
        // 本机是 mac：不该带 .exe（Windows 才带，那条分支由 isWindows 决定）
        check("非 Windows 上产物不带 .exe", !p.getFirst().getName().endsWith(".exe"), p.getFirst().getName());
    }

    // ---- 详情区该出现哪几节 ----

    static void sections() {
        List<DetailSection> compileFailed = SelfTestPanel.sections(
            new SelfTestService.Report("P1001", "/tmp/build/P1001", compile(false, "src:12:3: error: 少个分号"), null, null));
        check("编译失败有诊断节", section(compileFailed, "编译诊断") != null, String.valueOf(compileFailed));
        check("编译失败时诊断原文不裁",
            compileFailed.get(1).getBlock().contains("少个分号"), String.valueOf(compileFailed));
        check("编译失败不该出现 stdout 节", section(compileFailed, "stdout") == null, String.valueOf(compileFailed));
        check("概览要说编译失败", row(compileFailed, "概览", "编译").contains("失败"), row(compileFailed, "概览", "编译"));

        List<DetailSection> ok = SelfTestPanel.sections(
            new SelfTestService.Report("P1001", "/tmp/build/P1001", compile(true, ""),
                run("42\n", "warn: x", 0, 9L, false, false, false, null), null));
        check("跑通了 stdout 是那块内容", section(ok, "stdout").getBlock().equals("42\n"), section(ok, "stdout").getBlock());
        check("stderr 非空才出现这节", section(ok, "stderr") != null, "缺 stderr");
        check("概览带用时", row(ok, "概览", "用时").equals("9 ms"), row(ok, "概览", "用时"));
        check("概览带退出码", row(ok, "概览", "退出码").equals("0"), row(ok, "概览", "退出码"));
        check("概览第一行是题目", ok.get(0).getRows().get(0).getFirst().equals("题目"), String.valueOf(ok.get(0)));

        List<DetailSection> quiet = SelfTestPanel.sections(
            new SelfTestService.Report("P1001", "/tmp/build/P1001", compile(true, ""),
                run("1\n", "   ", 0, 1L, false, false, false, null), null));
        check("空白 stderr 不留一节空壳", section(quiet, "stderr") == null, String.valueOf(quiet));

        List<DetailSection> startErr = SelfTestPanel.sections(
            new SelfTestService.Report("P1001", "/tmp/build/P1001", null, null, "磁盘满了"));
        check("写不出临时文件也要写在概览里", row(startErr, "概览", "没能开始").equals("磁盘满了"), row(startErr, "概览", "没能开始"));

        List<DetailSection> running = SelfTestPanel.runningSections("P1001.cpp", "P1001", "/base/P1001.cpp", 1000, 128, null);
        check("运行中的那一块只有一节", running.size() == 1, String.valueOf(running.size()));
        check("运行中要说源文件", row(running, "正在跑", "源文件").equals("/base/P1001.cpp"), row(running, "正在跑", "源文件"));
        check("存过文件要说已保存", row(running, "正在跑", "编辑器").contains("已保存 P1001.cpp"), row(running, "正在跑", "编辑器"));
        check("没存过就别说已保存",
            SelfTestPanel.runningSections(null, "P1001", "/base/P1001.cpp", 1000, null, null).get(0).getRows().get(2).getSecond().equals("无需保存"),
            SelfTestPanel.runningSections(null, "P1001", "/base/P1001.cpp", 1000, null, null).get(0).getRows().get(2).getSecond());
    }

    static void exitCodeHints() {
        check("0xC00007B 要说 DLL", ProcessRunner.exitCodeHint(-1073741515).contains("缺少运行时 DLL"), ProcessRunner.exitCodeHint(-1073741515));
        check("134 要说 SIGABRT", ProcessRunner.exitCodeHint(134).contains("SIGABRT"), ProcessRunner.exitCodeHint(134));
        check("其他码只报码，不瞎解释", ProcessRunner.exitCodeHint(7).equals("退出码 7"), ProcessRunner.exitCodeHint(7));
    }

    // ---- 搬迁前就存在、这轮容易被连带改坏的判据 ----

    static void oldGuards() {
        // 提交页取哪一份
        check("countLines 空串 0 行", SubmitPanel.countLines("") == 0, String.valueOf(SubmitPanel.countLines("")));
        check("countLines 两行", SubmitPanel.countLines("a\nb") == 2, String.valueOf(SubmitPanel.countLines("a\nb")));
        check("countLines 结尾换行算一行", SubmitPanel.countLines("a\nb\n") == 3, String.valueOf(SubmitPanel.countLines("a\nb\n")));
        check("pickCodeOrigin 题号文件在第二个标签也算开着",
            SubmitPanel.pickCodeOrigin(Arrays.asList("main.cpp", "P1001.cpp"), "P1001.cpp", false) == CodeOrigin.EDITOR, "不是 EDITOR");
        check("pickCodeOrigin 没开就走磁盘",
            SubmitPanel.pickCodeOrigin(Arrays.asList("main.cpp"), "P1001.cpp", true) == CodeOrigin.DISK, "不是 DISK");
        check("pickCodeOrigin 大小写不敏感的卷",
            SubmitPanel.pickCodeOrigin(Arrays.asList("p1001.cpp"), "P1001.cpp", false) == CodeOrigin.EDITOR, "不是 EDITOR");
        check("pickCodeOrigin 两处都没有",
            SubmitPanel.pickCodeOrigin(Arrays.asList("x.cpp"), "P1001.cpp", false) == CodeOrigin.MISSING, "不是 MISSING");

        // 节标题剥离
        check("splitHeader 有头", "编译错误".equals(BlockText.INSTANCE.splitHeader("—— 编译错误 ——\nsrc:1:1: error").getFirst()),
            String.valueOf(BlockText.INSTANCE.splitHeader("—— 编译错误 ——\nsrc:1:1: error")));
        check("splitHeader 没头返回 null", BlockText.INSTANCE.splitHeader("第一行不是标题").getFirst() == null, "非 null");
        check("splitHeader 正文原样留下", BlockText.INSTANCE.splitHeader("—— X ——\nabc").getSecond().equals("abc"),
            BlockText.INSTANCE.splitHeader("—— X ——\nabc").getSecond());
        check("splitHeader 首个非空行是标题就剥（开头空行不算内容）",
            "X".equals(BlockText.INSTANCE.splitHeader("\n—— X ——\nabc").getFirst())
                && BlockText.INSTANCE.splitHeader("\n—— X ——\nabc").getSecond().equals("abc"),
            String.valueOf(BlockText.INSTANCE.splitHeader("\n—— X ——\nabc")));
        check("splitHeader 标题出现在正文中间不算标题",
            BlockText.INSTANCE.splitHeader("第一行不是标题\n—— X ——\nabc").getFirst() == null, "剥错了");

        // sectionsFromText：详情区还吃得下老式一整块文本
        check("sectionsFromText 空输入不炸（给空列表）", DetailPanel.sectionsFromText(null).isEmpty(), "非空");
        check("sectionsFromText 纯空白也给空列表", DetailPanel.sectionsFromText("   \n ").isEmpty(), "非空");

        // contextBlock 的行号是 1 起的（1.7.0 就是在这里错位过一行）
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 5; i++) lines.add("line" + i);
        String block = SampleDiff.INSTANCE.contextBlock("t", lines, 5, "共 5 行");
        check("contextBlock 最后一行能取到内容", block.contains("5: line5"), block);
        check("contextBlock 行号 1 起（错位一行就会显示 line4）", !block.contains("5: line4"), block);
        String first = SampleDiff.INSTANCE.contextBlock("t", lines, 1, "共 5 行");
        check("contextBlock 第一行不越界", first.contains("1: line1"), first);
        check("contextBlock 差异行有叉号", first.contains("✗"), first);
        check("contextBlock 该侧没这么长时要直说",
            SampleDiff.INSTANCE.contextBlock("t", lines, 9, null).contains("只到第 5 行"),
            SampleDiff.INSTANCE.contextBlock("t", lines, 9, null));

        // 跳行三层判定：1.7.3 的成果，别被这轮连带改坏
        String ce = "/tmp/compiler_abc/src:12:3: error: 少分号\n";
        check("choose 认洛谷那个没扩展名的 src",
            CompileErrorLocator.choose(ce, 50, true, "P1001.cpp").getHit() != null, "没命中");
        check("choose 行号超范围不放行",
            CompileErrorLocator.choose(ce, 5, true, "P1001.cpp").getMiss() == JumpMiss.LINE_OUT_OF_RANGE,
            String.valueOf(CompileErrorLocator.choose(ce, 5, true, "P1001.cpp").getMiss()));
        check("choose 标准库内部的不跳",
            CompileErrorLocator.choose("/usr/include/c++/vector:572:1: error: x\n", 50, true, "P1001.cpp").getMiss()
                == JumpMiss.FOREIGN_FILE, "跳错了");
        check("choose 命中自己那份文件算第①层",
            CompileErrorLocator.choose("P1001.cpp:3:1: error: x\n", 50, true, "P1001.cpp").getVia() == JumpSource.LOCAL_NAME,
            String.valueOf(CompileErrorLocator.choose("P1001.cpp:3:1: error: x\n", 50, true, "P1001.cpp").getVia()));
        check("choose 没诊断文本要说没得解析",
            CompileErrorLocator.choose(null, 50, true, "P1001.cpp").getMiss() == JumpMiss.NO_DIAGNOSTIC, "报错了原因");
        check("jumpLabel 命中时带行号",
            CompileErrorLocator.jumpLabel(CompileErrorLocator.choose(ce, 50, true, "P1001.cpp").getHit()).contains("12"),
            CompileErrorLocator.jumpLabel(CompileErrorLocator.choose(ce, 50, true, "P1001.cpp").getHit()));
    }

    // ---- 题面限制解析（他要「默认时空在题里」） ----

    static void limitsParsing() {
        String md = "# P1001 A+B 问题\n\n**难度**: 入门\n**时间限制**: 1000 ms\n**内存限制**: 131072 KB\n**分数**: 100\n";
        com.user.clionluogu.service.ProblemLimits.Limits l =
            com.user.clionluogu.service.ProblemLimits.INSTANCE.parse(md);
        check("题面时间解析成 ms", Integer.valueOf(1000).equals(l.getTimeMs()), String.valueOf(l.getTimeMs()));
        check("题面内存 KB → MB", Integer.valueOf(128).equals(l.getMemoryMb()), String.valueOf(l.getMemoryMb()));

        com.user.clionluogu.service.ProblemLimits.Limits none =
            com.user.clionluogu.service.ProblemLimits.INSTANCE.parse("**时间限制**: 暂无\n**内存限制**: 暂无\n");
        check("「暂无」不猜数字", none.getTimeMs() == null && none.getMemoryMb() == null, String.valueOf(none));
        check("整份没有那两行也不猜",
            com.user.clionluogu.service.ProblemLimits.INSTANCE.parse("# 标题\n正文\n").getTimeMs() == null, "有值");
        check("字段顺序颠倒也能读到",
            Integer.valueOf(500).equals(com.user.clionluogu.service.ProblemLimits.INSTANCE
                .parse("内存限制: 65536 KB\n时间限制: 500 ms\n").getTimeMs()), "读错");
        check("非正的内存当作没有",
            com.user.clionluogu.service.ProblemLimits.INSTANCE.memoryMbFromKb(0L) == null, "有值");
        check("KB 向上取整到 MB（1025 KB 就是跨进第 2 兆了）",
            Integer.valueOf(2).equals(com.user.clionluogu.service.ProblemLimits.INSTANCE.memoryMbFromKb(1025L)),
            String.valueOf(com.user.clionluogu.service.ProblemLimits.INSTANCE.memoryMbFromKb(1025L)));
        check("整兆的换算不动", Integer.valueOf(128).equals(
            com.user.clionluogu.service.ProblemLimits.INSTANCE.memoryMbFromKb(131072L)), "换错");
        check("时间字段空白退回插件默认",
            com.user.clionluogu.service.ProblemLimits.parseTimeMs("  ", 5000) == 5000,
            String.valueOf(com.user.clionluogu.service.ProblemLimits.parseTimeMs("  ", 5000)));
        check("时间越界是夹住而不是照用",
            com.user.clionluogu.service.ProblemLimits.parseTimeMs("1", 5000) == 50, "没夹住");
        check("时间写错不猜（回默认）",
            com.user.clionluogu.service.ProblemLimits.parseTimeMs("一秒", 5000) == 5000, "猜了");
        check("内存空白 = 不比", com.user.clionluogu.service.ProblemLimits.parseMemoryMb("") == null, "有值");
        check("内存超上限当作没填（不误判 MLE）",
            com.user.clionluogu.service.ProblemLimits.parseMemoryMb("999999") == null, "有值");
        check("两个面板共用同一条解析（自测那边只是补默认值）",
            SelfTestPanel.parseTimeMs(null) == SelfTestService.DEFAULT_TIMEOUT_MS,
            String.valueOf(SelfTestPanel.parseTimeMs(null)));
    }

    // ---- 磁盘签名：拉完题要能自己发现，又不能每秒都重探 ----

    static void signatureStability() {
        check("什么都没找到时签名稳定（不会每秒重探）",
            LocalRunSignature.of(null, null).equals(LocalRunSignature.of(null, null)), "变了");
        try {
            File tmp = Files.createTempFile("probe-sig", ".cpp").toFile();
            Files.write(tmp.toPath(), "int main(){}".getBytes("UTF-8"));
            String before = LocalRunSignature.of(tmp, null);
            check("同一份文件没动，签名不变", LocalRunSignature.of(tmp, null).equals(before), "变了");
            Files.write(tmp.toPath(), "int main(){return 1;}".getBytes("UTF-8"));
            check("文件一改签名就变（这就是重探的触发）",
                !LocalRunSignature.of(tmp, null).equals(before), "没变");
            File missing = new File("/没有这个目录/P1.cpp");
            check("文件不在了也能给出稳定签名",
                LocalRunSignature.of(missing, null).equals(LocalRunSignature.of(missing, null)), "变了");
            check("目录当文件用也不抛", LocalRunSignature.of(dirAsFile(), null) != null, "抛了");
        } catch (Exception e) {
            check("签名测试能建临时文件", false, e.toString());
        }
        check("源文件路径与探测用的同一个口径",
            LocalRunSignature.sourceFile("/base", "P1001").getPath().equals("/base/P1001.cpp"),
            String.valueOf(LocalRunSignature.sourceFile("/base", "P1001")));
        check("样例目录名与 SampleSetService 同源",
            LocalRunSignature.samplesDir("/base", "P1001").getName().equals("P1001_samples"),
            String.valueOf(LocalRunSignature.samplesDir("/base", "P1001")));
        check("题号为空时不猜路径", LocalRunSignature.sourceFile("/base", "") == null, "拼出来了");
    }

    static File dirAsFile() {
        try {
            return Files.createTempDirectory("probe-dir").toFile();
        } catch (Exception e) {
            return new File("/没有这个目录");
        }
    }

    // ---- 内存测量器：只认解析得出来的格式，解析不出就不判 ----

    static void meterParsing() {
        String mac = "Command being timed: \"x\"\n        real         0.00s\n        user         0.00s\n        sys          0.00s\n           1196272  peak memory footprint\n";
        check("mac 报表解析出字节峰值",
            Long.valueOf(1196272L).equals(ResourceMeter.parsePeakMac(mac)), String.valueOf(ResourceMeter.parsePeakMac(mac)));
        check("GNU 报表 KB → 字节",
            Long.valueOf(2097152L).equals(ResourceMeter.parsePeakGnu("        Maximum resident set size (kbytes): 2048\n")),
            String.valueOf(ResourceMeter.parsePeakGnu("Maximum resident set size (kbytes): 2048")));
        check("解析不出来就是 null（不编数字）", ResourceMeter.parsePeakMac("garbage") == null, "有值");
        check("0 不算峰值", ResourceMeter.parsePeakMac("0  peak memory footprint") == null, "有值");
        check("字节 → MB 向上取整", Integer.valueOf(2).equals(ResourceMeter.mb(1048577L)),
            String.valueOf(ResourceMeter.mb(1048577L)));
        check("量不到时不写 MB", ResourceMeter.mb(null) == null, "写了");
        check("剥报表只删认得出的行",
            ResourceMeter.stripReport("12 error: 我自己打的\n   1196272  peak memory footprint").equals("12 error: 我自己打的"),
            "[" + ResourceMeter.stripReport("12 error: 我自己打的\n   1196272  peak memory footprint") + "]");
        check("real/user/sys 那三行算报表", ResourceMeter.isReportLine("        real         0.00s"), "没认出来");
        check("程序自己的 stderr 不被当成报表",
            !ResourceMeter.isReportLine("Segmentation fault (core dumped)"), "误删了");

        ProcessRunner.Outcome noPeak = run("x", "", 0, 12L, false, false, false, null);
        ProcessRunner.Outcome withPeak = new ProcessRunner.Outcome("x", "", 0, 12L, false, false, false, null,
            200L * 1048576L, null);
        check("没设上限时峰值只是信息",
            !SelfTestService.summaryText(withPeak, 5000).contains("MLE"), SelfTestService.summaryText(withPeak, 5000));
        check("超上限判 MLE",
            SelfTestService.summaryText(withPeak, 5000, 128).contains("超过上限 128 MB"),
            SelfTestService.summaryText(withPeak, 5000, 128));
        check("量不到内存时绝不判 MLE（哪怕设了上限）",
            !SelfTestService.summaryText(noPeak, 5000, 128).contains("MLE"), SelfTestService.summaryText(noPeak, 5000, 128));
        check("超时优先于超内存",
            SelfTestService.summaryText(new ProcessRunner.Outcome("", "", null, 5000L, true, false, false, null,
                200L * 1048576L, null), 5000, 128).contains("TLE"), "判成 MLE 了");
        check("取消优先于超内存",
            SelfTestService.summaryText(new ProcessRunner.Outcome("", "", 143, 12L, false, false, true, null,
                200L * 1048576L, null), 5000, 128).equals("已取消"), "判成 MLE 了");
        check("stderr 那一节用剥过报表的那份",
            "boom".equals(SelfTestService.stderrBlock(new ProcessRunner.Outcome("", "boom\n   7  peak memory footprint",
                1, 1L, false, false, false, null, 7L, "boom"))), "没剥干净");
        check("空白 stderr 不留一节空壳",
            SelfTestService.stderrBlock(new ProcessRunner.Outcome("", "   \n", 0, 1L, false, false, false, null, null, "\n")) == null,
            "有值");
        check("量不到就不写「峰值内存」那一行", SelfTestService.peakMemoryText(noPeak) == null, "写了");
        check("量到了就写 MB", SelfTestService.peakMemoryText(withPeak).equals("200 MB"),
            String.valueOf(SelfTestService.peakMemoryText(withPeak)));
    }

    // ---- 状态行那一处规则（原来五个面板各写一遍，早晚有一处不染色） ----

    static void statusRowRule() {
        javax.swing.JLabel label = new javax.swing.JLabel();
        StatusRow.apply(label, "已提交到 P1001，记录 id=123", false);
        check("正常句用默认色（不是错误色）", !StatusRow.isErrorColor(label), String.valueOf(label.getForeground()));
        check("正常句不留残留 tooltip", label.getToolTipText() == null, String.valueOf(label.getToolTipText()));
        StatusRow.apply(label, "找不到 P1001.cpp", true);
        check("出错染成错误色", StatusRow.isErrorColor(label), String.valueOf(label.getForeground()));
        check("出错时整句进 tooltip（状态行只放得下一行）", "找不到 P1001.cpp".equals(label.getToolTipText()),
            String.valueOf(label.getToolTipText()));
        StatusRow.apply(label, "编译器：clang++", false);
        check("从错误回到正常要把颜色复原", !StatusRow.isErrorColor(label), "还是错误色");
        StatusRow.apply(label, "结果", false, "整段说明");
        check("显式传的 tooltip 原样用", "整段说明".equals(label.getToolTipText()), String.valueOf(label.getToolTipText()));
        check("firstLine 取第一条非空行", "第二行".equals(StatusRow.firstLine("\n  \n第二行\n第三行")),
            StatusRow.firstLine("\n  \n第二行\n第三行"));
        check("全空白给一个空格而不是空串（空串会让标签高度塌下去）", " ".equals(StatusRow.firstLine("   \n  ")),
            "[" + StatusRow.firstLine("   \n  ") + "]");
        javax.swing.JLabel warn = new javax.swing.JLabel();
        StatusRow.warn(warn, "设置里的编译器不可用，已回落");
        check("警告句也进 tooltip", "设置里的编译器不可用，已回落".equals(warn.getToolTipText()),
            String.valueOf(warn.getToolTipText()));
        StatusRow.clear(warn);
        check("clear 抹掉残留 tooltip", warn.getToolTipText() == null, String.valueOf(warn.getToolTipText()));
    }
}
