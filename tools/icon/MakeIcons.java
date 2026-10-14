import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/**
 * Generates the full Android launcher icon set from a single 1280x1280 source image:
 *   - legacy square  mipmap-&lt;density&gt;/ic_launcher.png        (48/72/96/144/192)
 *   - round variant  mipmap-&lt;density&gt;/ic_launcher_round.png  (same sizes, circular alpha mask)
 *   - adaptive foreground drawable-&lt;density&gt;/ic_launcher_foreground.png
 *                    (108dp canvas, artwork scaled to ~62% and centred inside the safe zone)
 *   - adaptive descriptors mipmap-anydpi-v26/ic_launcher{,_round}.xml
 *   - background colour values/ic_launcher_background.xml (average of the source image corners)
 *   - repository image docs/assets/icon.png (512x512, README/docs only, not packaged)
 *
 * Usage: java MakeIcons.java &lt;source image&gt; &lt;target res dir&gt;
 *
 * 由一张 1280x1280 源图生成 Android 全套启动图标：
 *   · 传统方图  mipmap-<density>/ic_launcher.png        (48/72/96/144/192)
 *   · 圆形图标  mipmap-<density>/ic_launcher_round.png  (同上，圆形 alpha 遮罩)
 *   · 自适应前景 drawable-<density>/ic_launcher_foreground.png
 *                (108dp 画布，画面缩到 ~62% 居中，落在安全区内)
 *   · 自适应描述 mipmap-anydpi-v26/ic_launcher{,_round}.xml
 *   · 背景色    values/ic_launcher_background.xml（取源图四角均色）
 *
 * 用法：java MakeIcons.java <源图> <目标 res 目录>
 *   例：java MakeIcons.java Lab.png app/src/lab/res
 */
public class MakeIcons {

    static final int[] LEGACY = {48, 72, 96, 144, 192};             // mdpi..xxxhdpi
    static final String[] DENS = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    static final int[] FG = {108, 162, 216, 324, 432};              // 108dp 前景画布

    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("用法: MakeIcons <源图> <目标res目录>"); System.exit(2); }
        BufferedImage src = ImageIO.read(new File(a[0]));
        if (src == null) { System.err.println("读不到图像: " + a[0]); System.exit(3); }
        Path res = Paths.get(a[1]);
        System.out.printf("源图 %s: %dx%d%n", a[0], src.getWidth(), src.getHeight());

        // 居中裁成正方形（非方图也不变形）
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage sq = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g0 = sq.createGraphics();
        g0.drawImage(src, 0, 0, side, side,
                (src.getWidth()-side)/2, (src.getHeight()-side)/2,
                (src.getWidth()-side)/2 + side, (src.getHeight()-side)/2 + side, null);
        g0.dispose();

        for (int i = 0; i < DENS.length; i++) {
            write(res.resolve("mipmap-" + DENS[i]).resolve("ic_launcher.png"), scale(sq, LEGACY[i]));
            write(res.resolve("mipmap-" + DENS[i]).resolve("ic_launcher_round.png"),
                  roundMask(scale(sq, LEGACY[i])));
            write(res.resolve("drawable-" + DENS[i]).resolve("ic_launcher_foreground.png"),
                  foreground(sq, FG[i]));
        }
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <background android:drawable=\"@color/ic_launcher_background\" />\n"
            + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
            + "</adaptive-icon>\n";
        Path anydpi = res.resolve("mipmap-anydpi-v26");
        Files.createDirectories(anydpi);
        Files.write(anydpi.resolve("ic_launcher.xml"), xml.getBytes("UTF-8"));
        Files.write(anydpi.resolve("ic_launcher_round.xml"), xml.getBytes("UTF-8"));

        // 额外产出一张 512x512 的仓库用图（README / 文档展示；不参与 APK 打包）
        // Also emit a 512x512 copy for the repository (README/docs only; not packaged into the APK).
        Path assets = Paths.get("docs/assets");
        Files.createDirectories(assets);
        write(assets.resolve("icon.png"), scale(sq, 512));

        int bg = cornerColor(sq);
        String color = String.format("#%06X", bg & 0xFFFFFF);
        Path vals = res.resolve("values");
        Files.createDirectories(vals);
        Files.write(vals.resolve("ic_launcher_background.xml"),
            ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
             + "    <color name=\"ic_launcher_background\">" + color + "</color>\n</resources>\n").getBytes("UTF-8"));
        System.out.println("完成 -> " + res + "  背景色 " + color);
    }

    static BufferedImage scale(BufferedImage s, int n) {
        BufferedImage d = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(s, 0, 0, n, n, null);
        g.dispose();
        return d;
    }

    /**
     * 圆形遮罩：必须用 setClip + drawImage。
     * 实测 AlphaComposite.SrcIn + fill(Ellipse) 在这里**不生效**（角像素 alpha 仍是 255，
     * 等于没遮罩）；用裁剪路径则角 alpha=0、心 alpha=255，符合预期。
     */
    static BufferedImage roundMask(BufferedImage s) {
        int n = s.getWidth();
        BufferedImage d = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setClip(new Ellipse2D.Float(0, 0, n, n));
        g.drawImage(s, 0, 0, null);
        g.dispose();
        return d;
    }

    /** 108dp 画布，画面缩到约 62% 居中（自适应图标的安全区） */
    static BufferedImage foreground(BufferedImage s, int canvas) {
        BufferedImage d = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int art = Math.round(canvas * 0.62f);
        int off = (canvas - art) / 2;
        g.drawImage(s, off, off, art, art, null);
        g.dispose();
        return d;
    }

    static int cornerColor(BufferedImage s) {
        int w = s.getWidth(), h = s.getHeight(), m = Math.max(1, w / 20);
        long r = 0, gg = 0, b = 0; int n = 0;
        int[][] pts = {{m, m}, {w-1-m, m}, {m, h-1-m}, {w-1-m, h-1-m}};
        for (int[] p : pts) {
            int c = s.getRGB(p[0], p[1]);
            r += (c >> 16) & 0xFF; gg += (c >> 8) & 0xFF; b += c & 0xFF; n++;
        }
        return (int)((r/n) << 16 | (gg/n) << 8 | (b/n));
    }

    static void write(Path p, BufferedImage img) throws IOException {
        Files.createDirectories(p.getParent());
        ImageIO.write(img, "png", p.toFile());
    }
}

