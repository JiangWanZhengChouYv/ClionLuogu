import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.user.clionluogu.service.CompilerService;
import com.user.clionluogu.service.ProcessRunner;
import com.user.clionluogu.service.SelfTestService;
import com.user.clionluogu.ui.DetailPanel;
import com.user.clionluogu.ui.DetailSection;
import com.user.clionluogu.ui.SelfTestPanel;
import com.user.clionluogu.ui.WrapLayout;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * 布局探针：把详情区与「头部那一行」放进真实的可显示容器里量。
 *
 * 两件事要它证明：
 * 1. **1.7.1 / 1.7.2 两次真坑**（普通 FlowLayout 折出去的第二行被整块裁掉）没被改回来 ——
 *    横排一律用 {@link WrapLayout}，它的 preferredSize 会把折行算进去；
 * 2. **1.8.0 第二轮排版的依据**：底部窗口矮，「一件一行」的头部（对拍原来 6 行、提交原来 5 行）
 *    要吃掉 150px 以上，横排一行只要 ~32px。这个数字是量出来的，不是估的。
 *
 * 判据只能量「直接子项有没有被裁到负坐标 / 超出父容器」，量不了「好不好看」；
 * 也不能只 setSize + doLayout —— 没有可显示的祖先时全量出 0x0，会得出假结论，所以真开 JFrame。
 */
public class LayoutProbe {

    static int clipped = 0;
    static int checks = 0;
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

    /** 只看这一层的直接子项（滚动窗格里的内容本来就该比视口大）。 */
    static void inspect(JComponent parent, String label) {
        for (Component child : parent.getComponents()) {
            if (!(child instanceof JComponent)) continue;
            JComponent c = (JComponent) child;
            if (!c.isVisible()) continue;
            Rectangle b = c.getBounds();
            Rectangle pb = parent.getBounds();
            boolean bad = b.x < 0 || b.y < 0 || b.x + b.width > pb.x + pb.width + 1 || b.y + b.height > pb.y + pb.height + 1;
            checks++;
            if (bad) {
                clipped++;
                System.out.println("  被裁 " + label + " · " + c.getClass().getSimpleName()
                    + (c instanceof JLabel ? "[" + ((JLabel) c).getText() + "]" : "")
                    + " bounds=" + b + " parent=" + pb);
            }
        }
    }

    static JPanel buildPanel() {
        DetailPanel detail = new DetailPanel();
        detail.render(SelfTestPanelSections.sample());

        JPanel root = new JPanel(new BorderLayout());
        // 头部也必须 WrapLayout：探针里用普通 FlowLayout「看起来没问题」，
        // 正是因为窄栏下被裁的那一行根本不在 preferredSize 里，量都量不出来
        JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        head.add(new JLabel("题号"));
        head.add(new JTextField());
        head.add(new JLabel("编译器：Apple clang version 17"));
        head.add(new JButton("换编译器…"));

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        actions.add(new JButton("运行"));
        actions.add(new JButton("停止"));
        actions.add(new JButton("跳到第 12 行"));
        actions.add(new JButton("去拉取"));
        actions.add(new JLabel("正常结束 · 退出码 0 · 用时 12 ms"));

        root.add(head, BorderLayout.NORTH);
        root.add(new JBScrollPane(detail), BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        return root;
    }

    public static void main(String[] args) throws Exception {
        final int[][] sizes = {
            {1200, 180},   // 底部窗口：宽而矮（默认高度）
            {900, 120},    // 更矮：只剩两三行的空间
            {500, 260},    // 窗口被拖窄（左边是 Project 工具窗口时）
            {460, 400},    // 回到侧边栏那种宽度，确认没反向退化
        };

        SwingUtilities.invokeAndWait(() -> {
            for (int[] size : sizes) {
                JPanel panel = buildPanel();
                JFrame frame = new JFrame("probe");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.getContentPane().add(panel);
                frame.pack();
                frame.setSize(size[0], size[1]);
                frame.validate();
                panel.setSize(size[0], size[1]);
                panel.doLayout();
                for (Component c : panel.getComponents()) {
                    if (c instanceof JComponent) inspect((JComponent) c, size[0] + "x" + size[1] + " 内层");
                }
                inspect(panel, size[0] + "x" + size[1]);
                System.out.println("  " + size[0] + "x" + size[1] + "：首选高=" + panel.getPreferredSize().height
                    + "，实际高=" + panel.getHeight());
                frame.dispose();
            }
            headerExperiment();
            blockMinimumHeight();
        });

        System.out.println("布局探针：检查 " + checks + " 个位置，被裁 " + clipped + " 个；尺寸断言 "
            + pass + " 通过 / " + fail + " 失败");
        if (clipped > 0 || fail > 0) System.out.println("结论：有问题，需要改排版");
        else System.out.println("结论：四档尺寸没有被裁的组件，头部与详情区的尺寸断言全过");
        Runtime.getRuntime().halt(clipped == 0 && fail == 0 ? 0 : 1);
    }

    /** 同样这些控件：竖排六行 vs 横排一行，差多少高度 —— 本轮改动的实测依据。 */
    static void headerExperiment() {
        JPanel stacked = new JPanel(new GridBagLayout());
        for (int i = 0; i < 6; i++) {
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = i;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = JBUI.insets(2);
            stacked.add(new JTextField(), gbc);
        }
        int tall = stacked.getPreferredSize().height;
        System.out.println("  旧形状（一件一行 ×6）高度 = " + tall);
        check("一件一行的头部确实吃掉了整个底部窗口", tall > 150, String.valueOf(tall));

        Component[] widgets = {
            new JLabel("题号"), new JTextField(),
            new JLabel("时限"), new JTextField(),
            new JLabel("内存"), new JTextField(),
            new JLabel("编译器：clang++"), new JLabel(" "),
            new JButton("换编译器…"),
        };
        JPanel wide = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : widgets) wide.add(c);
        wide.setSize(1200, 200);
        wide.doLayout();
        int oneRow = wide.getPreferredSize().height;
        System.out.println("  新形状（横排一行，1200px 宽）高度 = " + oneRow);
        check("一行头部不超过 40 像素", oneRow <= 40, String.valueOf(oneRow));
        check("省下来的高度够放四五行输出", tall - oneRow >= 120, String.valueOf(tall - oneRow));

        JPanel narrow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : widgets) narrow.add(c);
        narrow.setSize(240, 200);
        narrow.doLayout();
        check("拖窄时 WrapLayout 折行并把折出的行算进首选高度（普通 FlowLayout 在这里会裁掉第二行）",
            narrow.getPreferredSize().height > oneRow, String.valueOf(narrow.getPreferredSize().height));
    }

    /** 详情区每块的最小高度：矮窗口里不能被压成一条缝。 */
    static void blockMinimumHeight() {
        List<DetailSection> many = new ArrayList<>();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 40; i++) longText.append("诊断第 ").append(i).append(" 行\n");
        many.add(new DetailSection("stdout", new ArrayList<>(), "1\n2\n3\n4\n5"));
        many.add(new DetailSection("stderr", new ArrayList<>(), "a\nb\nc"));
        many.add(new DetailSection("编译诊断", new ArrayList<>(), longText.toString()));
        DetailPanel panel = new DetailPanel();
        panel.render(many);

        int blocks = 0;
        int minBlock = Integer.MAX_VALUE;
        for (Component c : panel.getComponents()) {
            if (!(c instanceof JPanel)) continue;
            JPanel wrap = (JPanel) c;
            if (wrap.getComponentCount() == 0) continue;
            Component inner = wrap.getComponent(0);
            if (!(inner instanceof JBScrollPane)) continue;
            Dimension d = inner.getPreferredSize();
            if (d.height <= 0) continue;
            blocks++;
            minBlock = Math.min(minBlock, d.height);
        }
        System.out.println("  详情区块数 = " + blocks + "，最小一块高度 = " + minBlock);
        check("三块都渲染出来", blocks == 3, String.valueOf(blocks));
        check("每块至少约三行高，不会被压成一条缝", minBlock >= 40, String.valueOf(minBlock));
    }
}

/** 造一份「跑通了 + 有 stderr」的详情数据（面板在没起 IDE 时没法实例化，这里只量它的详情区）。 */
class SelfTestPanelSections {

    static List<DetailSection> sample() {
        CompilerService.Outcome compile = new CompilerService.Outcome(true, 0, "", 30L, "clang++ …", false, false);
        ProcessRunner.Outcome run = new ProcessRunner.Outcome(
            "6\n", "warn: something\n", 0, 12L, false, false, false, null, 44564480L, "warn: something\n");
        SelfTestService.Report report = new SelfTestService.Report("P1001", "/tmp/build/P1001", compile, run, null);
        java.util.List<DetailSection> both = new java.util.ArrayList<>();
        both.addAll(SelfTestPanel.sideSections(report, 128, 1000));
        both.addAll(SelfTestPanel.outputSections(report));
        return both;
    }
}
