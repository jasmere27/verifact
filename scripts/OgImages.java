import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates the 1200x630 Open Graph images used for link previews of shared reports
 * (frontend/public/og/*.png). Colours match the light theme in frontend/src/index.css.
 *
 * Run from the repo root:  java scripts/OgImages.java
 */
public class OgImages {

    static final int W = 1200, H = 630;
    static final Color BG = new Color(0xf7f6f3), TEXT = new Color(0x1b1d21), MUTED = new Color(0x5b6068),
            ACCENT = new Color(0x1d4f8c), LINE = new Color(0xe2e0da);
    static final String SERIF_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf";
    static final String SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    static final String SANS_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf";

    record Card(String file, String label, String meaning, Color color, String icon) {}

    public static void main(String[] args) throws Exception {
        File out = new File("frontend/public/og");
        out.mkdirs();
        Card[] cards = {
                new Card("supported", "Supported", "The sources found back this up.", new Color(0x1c6b3f), "check"),
                new Card("partly_supported", "Partly supported", "Some parts hold up; some details don't.", new Color(0x4f6214), "half"),
                new Card("misleading", "Misleading", "The facts may be real, but the framing misleads.", new Color(0x8f5100), "warn"),
                new Card("contradicted", "Contradicted", "The sources found say otherwise.", new Color(0xb0261c), "cross"),
                new Card("insufficient_evidence", "Not enough evidence", "The sources found don't settle it either way.", new Color(0x545b66), "question"),
                new Card("mixed", "Mixed results", "The claims checked received different verdicts.", new Color(0x6a4596), "mixed"),
                new Card("default", "Check claims against the evidence", "Paste a claim or link. See what the sources say.", ACCENT, "check"),
        };
        Font serifBold = Font.createFont(Font.TRUETYPE_FONT, new File(SERIF_BOLD));
        Font sans = Font.createFont(Font.TRUETYPE_FONT, new File(SANS));
        Font sansBold = Font.createFont(Font.TRUETYPE_FONT, new File(SANS_BOLD));
        for (Card c : cards) {
            ImageIO.write(render(c, serifBold, sans, sansBold), "png", new File(out, c.file() + ".png"));
            System.out.println("wrote og/" + c.file() + ".png");
        }
    }

    static BufferedImage render(Card c, Font serifBold, Font sans, Font sansBold) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setColor(c.color());
        g.fillRect(0, 0, 18, H);

        // Brand: logo mark + wordmark
        int bx = 80, by = 64;
        g.setColor(ACCENT);
        g.fill(new RoundRectangle2D.Double(bx, by, 52, 52, 12, 12));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D tick = new Path2D.Double();
        tick.moveTo(bx + 14, by + 27);
        tick.lineTo(bx + 23, by + 36);
        tick.lineTo(bx + 39, by + 18);
        g.draw(tick);
        g.setColor(TEXT);
        g.setFont(sansBold.deriveFont(38f));
        g.drawString("VeriFact", bx + 70, by + 40);

        // Verdict icon
        int ix = 80, iy = 210, is = 150;
        drawIcon(g, c.icon(), c.color(), ix, iy, is);

        // Verdict label (shrinks to fit) and meaning
        int tx = ix + is + 50, maxW = W - tx - 70;
        float size = 96f;
        Font label = serifBold.deriveFont(size);
        while (g.getFontMetrics(label).stringWidth(c.label()) > maxW && size > 44f) {
            size -= 4f;
            label = serifBold.deriveFont(size);
        }
        g.setFont(label);
        g.setColor(c.color());
        FontMetrics lm = g.getFontMetrics();
        int labelBaseline = iy + is / 2 + lm.getAscent() / 2 - 12;
        drawWrapped(g, c.label(), tx, labelBaseline, maxW, lm.getHeight());

        g.setFont(sans.deriveFont(34f));
        g.setColor(TEXT);
        drawWrapped(g, c.meaning(), tx, labelBaseline + 70 + (lm.stringWidth(c.label()) > maxW ? lm.getHeight() : 0),
                maxW, 44);

        // Footer
        g.setColor(LINE);
        g.fillRect(80, H - 110, W - 160, 2);
        g.setColor(MUTED);
        g.setFont(sans.deriveFont(28f));
        g.drawString("Verify Truth, Fight the False · evidence-based claim checks", 80, H - 60);

        g.dispose();
        return img;
    }

    static void drawIcon(Graphics2D g, String icon, Color color, int x, int y, int s) {
        g.setColor(color);
        g.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Ellipse2D circle = new Ellipse2D.Double(x + 6, y + 6, s - 12, s - 12);
        switch (icon) {
            case "check" -> {
                g.fill(circle);
                g.setColor(Color.WHITE);
                Path2D p = new Path2D.Double();
                p.moveTo(x + s * 0.28, y + s * 0.52);
                p.lineTo(x + s * 0.44, y + s * 0.68);
                p.lineTo(x + s * 0.73, y + s * 0.36);
                g.draw(p);
            }
            case "cross" -> {
                g.fill(circle);
                g.setColor(Color.WHITE);
                g.drawLine((int) (x + s * 0.34), (int) (y + s * 0.34), (int) (x + s * 0.66), (int) (y + s * 0.66));
                g.drawLine((int) (x + s * 0.66), (int) (y + s * 0.34), (int) (x + s * 0.34), (int) (y + s * 0.66));
            }
            case "half" -> {
                g.draw(circle);
                g.fill(new Arc2D.Double(x + 6, y + 6, s - 12, s - 12, 90, 180, Arc2D.PIE));
            }
            case "warn" -> {
                Path2D t = new Path2D.Double();
                t.moveTo(x + s * 0.5, y + s * 0.08);
                t.lineTo(x + s * 0.95, y + s * 0.88);
                t.lineTo(x + s * 0.05, y + s * 0.88);
                t.closePath();
                g.fill(t);
                g.setColor(Color.WHITE);
                g.drawLine((int) (x + s * 0.5), (int) (y + s * 0.38), (int) (x + s * 0.5), (int) (y + s * 0.6));
                g.fill(new Ellipse2D.Double(x + s * 0.5 - 8, y + s * 0.72 - 8, 16, 16));
            }
            case "question" -> {
                g.setStroke(new BasicStroke(10f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{18f, 12f}, 0f));
                g.draw(circle);
                g.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(x + s * 0.36, y + s * 0.24, s * 0.28, s * 0.26, 160, -250, Arc2D.OPEN));
                g.drawLine((int) (x + s * 0.5), (int) (y + s * 0.5), (int) (x + s * 0.5), (int) (y + s * 0.6));
                g.fill(new Ellipse2D.Double(x + s * 0.5 - 8, y + s * 0.74 - 8, 16, 16));
            }
            case "mixed" -> {
                g.draw(circle);
                for (double f : new double[]{0.38, 0.5, 0.62}) {
                    g.drawLine((int) (x + s * 0.3), (int) (y + s * f), (int) (x + s * 0.7), (int) (y + s * f));
                }
            }
            default -> g.fill(circle);
        }
    }

    static void drawWrapped(Graphics2D g, String text, int x, int y, int maxW, int lineH) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int cy = y;
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxW && !line.isEmpty()) {
                g.drawString(line.toString(), x, cy);
                cy += lineH;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            g.drawString(line.toString(), x, cy);
        }
    }
}
