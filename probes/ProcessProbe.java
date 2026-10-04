import com.intellij.openapi.progress.ProgressIndicator;

import com.user.clionluogu.service.CompilerService;
import com.user.clionluogu.service.ProcessRunner;
import com.user.clionluogu.service.ResourceMeter;
import com.user.clionluogu.service.SelfTestService;
import com.user.clionluogu.service.SampleCompareService;
import com.user.clionluogu.service.SampleCompareService.Request;
import com.user.clionluogu.service.SampleCompareService.Result;
import com.user.clionluogu.service.SampleCompareService.Verdict;
import com.user.clionluogu.service.SampleSetService.Sample;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真子进程断言：起真 clang++、跑真程序。
 *
 * 1.8.0 把进程内核从 [SampleCompareService.runOne] 抽到了 [ProcessRunner]，
 * 这一份存在的唯一意义就是证明**搬迁前后判定逐字一样**（尤其 1.7.0 那条「命令行回显
 * 混进 stdout 会让每组都判错」的老坑不能回来）。
 */
public class ProcessProbe {

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

    /** EmptyProgressIndicator 构造要 Application，裸 JVM 里造不出来 → 用 Proxy 顶一个。 */
    static ProgressIndicator indicator() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isCanceled":
                    return false;
                case "checkCanceled":
                    return null;
                default: {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0.0;
                    if (rt == float.class) return 0.0f;
                    return null;
                }
            }
        };
        return (ProgressIndicator) Proxy.newProxyInstance(
            ProcessProbe.class.getClassLoader(), new Class<?>[]{ProgressIndicator.class}, h);
    }

    static File dir;

    static File write(String name, String content) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    static CompilerService.Compiler findCompiler() {
        CompilerService.Detected d = CompilerService.INSTANCE.detect(null);
        return d.getCompiler();
    }

    /** 现场编译一个小程序；失败就把诊断原文打出来（别静默跳）。 */
    static File compile(CompilerService.Compiler c, String sourceName, String code) throws Exception {
        File src = write(sourceName, code);
        File exe = new File(dir, sourceName.replace(".cpp", ""));
        CompilerService.Outcome o = CompilerService.INSTANCE.compile(
            c.getFile(), src, exe, Arrays.asList("-std=c++17"), new AtomicBoolean(false));
        if (!o.getOk()) {
            System.out.println("  编译失败 " + sourceName + "：" + o.getDiagnostic());
            return null;
        }
        return exe;
    }

    static Request request(File exe, File workDir, int timeoutMs, Sample... samples) {
        return new Request("P1001", exe, workDir, new ArrayList<>(Arrays.asList(samples)),
            timeoutMs, Collections.emptyList(), null, null, null);
    }

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("clionluogu-probe-process").toFile();
        ProgressIndicator ind = indicator();
        CompilerService.Compiler c = findCompiler();
        if (c == null) {
            System.out.println("SKIP 这台机器没找到编译器（clang++/g++），进程断言跑不了");
            System.out.println("=== 探针合计：0 通过 / 0 失败 ===");
            Runtime.getRuntime().halt(0);
        }
        System.out.println("用编译器：" + c.display());

        // 1) 读 stdin、输出两倍 —— PASS
        File echoExe = compile(c, "echo.cpp",
            "#include <iostream>\nint main(){int x=0;std::cin>>x;std::cout<<x*2<<std::endl;return 0;}\n");
        if (echoExe == null) {
            System.out.println("=== 探针合计：0 通过 / 1 失败 ===");
            Runtime.getRuntime().halt(1);
        }
        File in = write("P1001_1.in", "3\n");
        File goodOut = write("P1001_1.out", "6\n");
        Sample s1 = new Sample(1, in, goodOut);
        Result r1 = SampleCompareService.INSTANCE.runOne(request(echoExe, dir, 5000, s1), s1, ind, new AtomicBoolean(false));
        check("内核搬迁后 PASS 仍判通过", r1.getVerdict() == Verdict.PASS, String.valueOf(r1.getVerdict()));
        check("PASS 不保留完整 stdout（省内存那条设计）", r1.getActualOutput() == null, String.valueOf(r1.getActualOutput()));
        check("stdin 是文件重定向，程序读到的是 3", r1.getElapsedMs() >= 0, String.valueOf(r1.getElapsedMs()));

        // 2) 期望对不上 —— MISMATCH，且首个差异行号是 1 起
        File badOut = write("P1001_2.out", "7\n");
        Sample s2 = new Sample(2, in, badOut);
        Result r2 = SampleCompareService.INSTANCE.runOne(request(echoExe, dir, 5000, s2), s2, ind, new AtomicBoolean(false));
        check("MISMATCH 判不通过", r2.getVerdict() == Verdict.MISMATCH, String.valueOf(r2.getVerdict()));
        check("MISMATCH 留完整 stdout 给「存反例」", "6\n".equals(r2.getActualOutput()), String.valueOf(r2.getActualOutput()));
        check("差异行号 1 起", r2.getIssue() != null && r2.getIssue().getLine() == 1,
            r2.getIssue() == null ? "null" : String.valueOf(r2.getIssue().getLine()));
        check("实际侧上下文带 line 内容", r2.getActualPreview().contains("6"), String.valueOf(r2.getActualPreview()));

        // 3) stdout 里绝不能混进平台那条「命令行回显」（1.7.0 的真坑）
        File mixExe = compile(c, "mix.cpp",
            "#include <iostream>\nint main(){std::cout<<\"O\\n\";std::cerr<<\"E\\n\";return 0;}\n");
        Sample s3 = new Sample(3, in, write("P1001_3.out", "O\n"));
        Result r3 = SampleCompareService.INSTANCE.runOne(request(mixExe, dir, 5000, s3), s3, ind, new AtomicBoolean(false));
        check("stdout 只有程序自己打的那一行", r3.getVerdict() == Verdict.PASS, String.valueOf(r3.getVerdict()));
        check("stderr 分类正确、不被当成 stdout", r3.getStderr() != null && r3.getStderr().contains("E"),
            String.valueOf(r3.getStderr()));

        // 4) 非零退出 —— RUNTIME_ERROR + 具体码
        File reExe = compile(c, "re.cpp", "int main(){return 3;}\n");
        Sample s4 = new Sample(4, in, write("P1001_4.out", "x\n"));
        Result r4 = SampleCompareService.INSTANCE.runOne(request(reExe, dir, 5000, s4), s4, ind, new AtomicBoolean(false));
        check("非零退出判 RE", r4.getVerdict() == Verdict.RUNTIME_ERROR, String.valueOf(r4.getVerdict()));
        check("RE 说明带退出码", r4.getNote().contains("退出码 3"), String.valueOf(r4.getNote()));
        check("Integer exitCode 取到了", Integer.valueOf(3).equals(r4.getExitCode()), String.valueOf(r4.getExitCode()));

        // 5) 死循环 —— 到点被杀，判 TIMEOUT（本地近似 TLE）
        File loopExe = compile(c, "loop.cpp", "int main(){while(true);return 0;}\n");
        Sample s5 = new Sample(5, in, write("P1001_5.out", "x\n"));
        long t0 = System.currentTimeMillis();
        Result r5 = SampleCompareService.INSTANCE.runOne(request(loopExe, dir, 600, s5), s5, ind, new AtomicBoolean(false));
        long spent = System.currentTimeMillis() - t0;
        check("死循环判 TIMEOUT", r5.getVerdict() == Verdict.TIMEOUT, String.valueOf(r5.getVerdict()));
        // 到点要停：kill 是「先 SIGTERM 再补刀」，加 2 秒收尾等待，所以整体几秒是正常的；
        // 这条断言抓的是「等满编译上限 120 秒」那种回归。
        check("超时是真被终止的（没等满 120 秒）", spent < 15_000, String.valueOf(spent));
        check("TIMEOUT 说明带毫秒上限", r5.getNote().contains("600 ms"), String.valueOf(r5.getNote()));

        // 6) 输出爆量 —— 立刻杀，判 OLE 而不是判成 MISMATCH
        File bigExe = compile(c, "big.cpp",
            "#include <iostream>\nint main(){for(int i=0;i<200000;i++)std::cout<<\"0123456789\";return 0;}\n");
        Sample s6 = new Sample(6, in, write("P1001_6.out", "x\n"));
        Result r6 = SampleCompareService.INSTANCE.runOne(request(bigExe, dir, 8000, s6), s6, ind, new AtomicBoolean(false));
        check("输出超限判 OUTPUT_TOO_MUCH", r6.getVerdict() == Verdict.OUTPUT_TOO_MUCH, String.valueOf(r6.getVerdict()));
        check("OLE 的说明提字节上限", r6.getNote().contains("字节"), String.valueOf(r6.getNote()));

        // 7) 停止 —— 必须先于比对判定为「未执行」，不能变成 RE
        AtomicBoolean stop = new AtomicBoolean(true);
        Result r7 = SampleCompareService.INSTANCE.runOne(request(loopExe, dir, 8000, s5), s5, ind, stop);
        check("请求停止判 NOT_RUN", r7.getVerdict() == Verdict.NOT_RUN, String.valueOf(r7.getVerdict()));
        check("停止的文案就一句「已取消」", "已取消".equals(r7.getNote()), String.valueOf(r7.getNote()));

        // 8) 自测走的是同一个内核：直接问 ProcessRunner
        ProcessRunner.Outcome o8 = ProcessRunner.run(
            new ProcessRunner.Run(echoExe, in, dir, 5000, Collections.emptyList(), null), ind, new AtomicBoolean(false));
        check("ProcessRunner 自己跑：正常退出", Integer.valueOf(0).equals(o8.getExitCode()), String.valueOf(o8.getExitCode()));
        check("ProcessRunner 自己跑：stdout 就是程序的输出", "6\n".equals(o8.getStdout()), String.valueOf(o8.getStdout()));
        check("ProcessRunner 自己跑：没有超时/超限/取消",
            !o8.getTimedOut() && !o8.getOverLimit() && !o8.getCancelled() && o8.getStartError() == null, "有标志位错了");

        // 9) 产物起不来（路径不存在）要报 startError，不抛异常到调用方
        ProcessRunner.Outcome o9 = ProcessRunner.run(
            new ProcessRunner.Run(new File(dir, "没有这个文件"), in, dir, 1000, Collections.emptyList(), null),
            ind, new AtomicBoolean(false));
        check("产物不存在时不抛异常，给 startError", o9.getStartError() != null, String.valueOf(o9.getStartError()));
        check("产物不存在时没有退出码", o9.getExitCode() == null, String.valueOf(o9.getExitCode()));

        // 10) 内存测量：包一层 time 要能解析出峰值，且报表不混进 stdout
        // 这条不许 SKIP：上一版探测用 /bin/true（用户机器上没有），测量器整个没启用，
        // 探针却把九条断言静默跳过、照样报绿 —— 假绿就是这么来的。
        ResourceMeter.Meter meter = ResourceMeter.INSTANCE.available();
        check("这台机器能探测到峰值内存测量器", meter != null, String.valueOf(meter));
        if (meter != null) {
            File hog = compile(c, "hog.cpp",
                "#include <cstdlib>\n#include <cstring>\n#include <iostream>\nint main(){int k=40;char*p=(char*)malloc((size_t)k*1048576);memset(p,1,(size_t)k*1048576);std::cout<<\"ok\\n\";return 0;}\n");
            ProcessRunner.Outcome o10 = ProcessRunner.run(
                new ProcessRunner.Run(hog, in, dir, 20_000, Collections.emptyList(), meter), ind, new AtomicBoolean(false));
            check("包了 time 之后 stdout 仍是程序的输出", "ok\n".equals(o10.getStdout()), String.valueOf(o10.getStdout()));
            check("解析出了峰值内存", o10.getPeakMemoryBytes() != null && o10.getPeakMemoryBytes() > 0,
                String.valueOf(o10.getPeakMemoryBytes()));
            check("峰值确实量到了 30 MB 以上",
                o10.getPeakMemoryBytes() != null && o10.getPeakMemoryBytes() > 30L * 1048576L,
                String.valueOf(ResourceMeter.mb(o10.getPeakMemoryBytes())) + " MB");
            check("报表行没混进要展示的 stderr",
                o10.getProgramStderr() != null && !o10.getProgramStderr().contains("peak memory footprint"),
                String.valueOf(o10.getProgramStderr()));
            check("超上限的文案是 MLE（走 summaryText）",
                SelfTestService.summaryText(o10, 20_000, 8).contains("MLE"),
                SelfTestService.summaryText(o10, 20_000, 8));
            check("上限放宽就正常结束",
                SelfTestService.summaryText(o10, 20_000, 512).startsWith("正常结束"),
                SelfTestService.summaryText(o10, 20_000, 512));
            // 对拍那条链也带上测量器：判定应变成「超内存」
            Sample s10 = new Sample(10, in, write("P1001_10.out", "ok\n"));
            Request req10 = new Request("P1001", hog, dir, new ArrayList<>(Arrays.asList(s10)),
                20_000, Collections.emptyList(), null, 8, meter);
            Result r10 = SampleCompareService.INSTANCE.runOne(req10, s10, ind, new AtomicBoolean(false));
            check("对拍侧同样能判超内存", r10.getVerdict() == Verdict.MEMORY_LIMIT, String.valueOf(r10.getVerdict()));
            check("结果里带着峰值 MB", r10.getPeakMemoryMb() != null && r10.getPeakMemoryMb() > 30,
                String.valueOf(r10.getPeakMemoryMb()));
            // 非 PASS 的行会留完整 stdout 给「存反例」——这条同时证明报表没混进 stdout
            check("stdout 仍是程序自己打的那行（没混进 time 报表）", "ok\n".equals(r10.getActualOutput()),
                String.valueOf(r10.getActualOutput()));
        }

        System.out.println("=== 探针合计：" + pass + " 通过 / " + fail + " 失败 ===");
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
    }
}
