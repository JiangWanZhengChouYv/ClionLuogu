import com.intellij.ui.components.JBScrollPane;
import com.user.clionluogu.service.CompilerService;
import com.user.clionluogu.service.ProcessRunner;
import com.user.clionluogu.service.SelfTestService;
import com.user.clionluogu.ui.DetailPanel;
import com.user.clionluogu.ui.DetailSection;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.util.List;

/**
 * 布局探针：把详情区放进「底部窗口那种形状」里量一遍。
 *
 * 为什么单开一份：1.7.1 与 1.7.2 两次真坑都是**窄侧边栏把第二行裁掉**，
 * 而 1.8.0 新增的底部窗口是相反的病 —— **宽而矮**（Run 窗口默认一百多像素高）。
 * 方向不一样，`AutoFlipSplitter`（按宽度换向）在这里不救场，必须实测。
 *
 * 判据只能量「直接子项有没有被裁到负坐标 / 超出父容器」，不能量「好不好看」；
 * 也不能只 `setSize + doLayout` —— 没有可显示的祖先时全量出 0x0，会得出假结论，
 * 所以这里真的开一个 JFrame 再 pack。
 */
public class LayoutProbe {

    static int clipped = 0;
    static int checks = 0;

    /** 只看这一层的直接子项（滚动窗格里的内容本来就该比视口大）。 */
    static void inspect(JComponent parent, String label) {
        for (java.awt.Component child : parent.getComponents()) {
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
        List<DetailSection> sections = SelfTestPanelSections.sample();
        detail.render(sections);

        JPanel root = new JPanel(new BorderLayout());
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        head.add(new JLabel("题号"));
        head.add(new JButton("P1001"));
        head.add(new JLabel("编译器：Apple clang version 17"));
        head.add(new JButton("换编译器…"));

        JPanel actions = new JPanel(new com.user.clionluogu.ui.WrapLayout(FlowLayout.LEFT, 6, 2));
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
                for (java.awt.Component c : panel.getComponents()) {
                    if (c instanceof JComponent) inspect((JComponent) c, size[0] + "x" + size[1] + " 内层");
                }
                inspect(panel, size[0] + "x" + size[1]);
                System.out.println("  " + size[0] + "x" + size[1] + "：首选高=" + panel.getPreferredSize().height
                    + "，实际高=" + panel.getHeight());
                frame.dispose();
            }
        });

        System.out.println("布局探针：检查 " + checks + " 个位置，被裁 " + clipped + " 个");
        if (clipped > 0) System.out.println("结论：有组件被裁，需要改排版");
        else System.out.println("结论：四档尺寸都没有被裁的组件");
        Runtime.getRuntime().halt(clipped == 0 ? 0 : 1);
    }
}

/** 造一份「跑通了 + 有 stderr」的详情数据（面板在没起 IDE 时没法实例化，这里只量它的详情区）。 */
class SelfTestPanelSections {

    static List<DetailSection> sample() {
        CompilerService.Outcome compile = new CompilerService.Outcome(true, 0, "", 30L, "clang++ …", false, false);
        ProcessRunner.Outcome run = new ProcessRunner.Outcome(
            "6\n", "warn: something\n", 0, 12L, false, false, false, null, 44564480L, "warn: something\n");
        SelfTestService.Report report = new SelfTestService.Report("P1001", "/tmp/build/P1001", compile, run, null);
        return com.user.clionluogu.ui.SelfTestPanel.sections(report, 128);
    }
}
