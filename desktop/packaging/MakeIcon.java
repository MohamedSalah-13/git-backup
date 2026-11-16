import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws the application icon and writes it in the formats the build needs:
 * src/main/resources/icon-{16,32,48,256}.png (window and tray) and packaging/app.ico
 * (installer and .exe). The results are committed; run this only to change the icon:
 *
 * <pre>java packaging\MakeIcon.java .</pre>
 */
public class MakeIcon {
    private static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 256};
    private static final int[] PNG_SIZES = {16, 32, 48, 256};

    public static void main(String[] args) throws IOException {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path resources = project.resolve("src/main/resources");
        for (int size : PNG_SIZES) {
            ImageIO.write(draw(size), "png", resources.resolve("icon-" + size + ".png").toFile());
        }
        Files.write(project.resolve("packaging/app.ico"), ico());
        System.out.println("Icon files written under " + project);
    }

    /** A blue rounded square with a white arrow dropping into a tray. */
    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size;
        g.setPaint(new GradientPaint(0, 0, new Color(0x2F81F7), 0, s, new Color(0x1557C0)));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.44f, s * 0.44f));

        g.setPaint(Color.WHITE);
        Path2D.Float arrow = new Path2D.Float();
        arrow.moveTo(s * 0.43f, s * 0.18f);
        arrow.lineTo(s * 0.57f, s * 0.18f);
        arrow.lineTo(s * 0.57f, s * 0.42f);
        arrow.lineTo(s * 0.72f, s * 0.42f);
        arrow.lineTo(s * 0.50f, s * 0.66f);
        arrow.lineTo(s * 0.28f, s * 0.42f);
        arrow.lineTo(s * 0.43f, s * 0.42f);
        arrow.closePath();
        g.fill(arrow);

        g.setStroke(new BasicStroke(s * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Float tray = new Path2D.Float();
        tray.moveTo(s * 0.22f, s * 0.62f);
        tray.lineTo(s * 0.22f, s * 0.80f);
        tray.lineTo(s * 0.78f, s * 0.80f);
        tray.lineTo(s * 0.78f, s * 0.62f);
        g.draw(tray);
        g.dispose();
        return image;
    }

    private static byte[] ico() throws IOException {
        byte[][] images = new byte[ICO_SIZES.length][];
        for (int i = 0; i < ICO_SIZES.length; i++) {
            images[i] = ICO_SIZES[i] >= 256 ? png(draw(ICO_SIZES[i])) : dib(draw(ICO_SIZES[i]));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * images.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) images.length);
        int offset = header.capacity();
        for (int i = 0; i < images.length; i++) {
            int size = ICO_SIZES[i];
            header.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size));
            header.put((byte) 0).put((byte) 0);
            header.putShort((short) 1).putShort((short) 32);
            header.putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        bytes.write(header.array());
        for (byte[] image : images) {
            bytes.write(image);
        }
        return bytes.toByteArray();
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A 32-bit bitmap as .ico stores it: bottom-up BGRA rows followed by an (empty) 1-bit mask. */
    private static byte[] dib(BufferedImage image) throws IOException {
        int size = image.getWidth();
        int maskRow = ((size + 31) / 32) * 4;
        ByteBuffer buffer = ByteBuffer.allocate(40 + size * size * 4 + maskRow * size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(40).putInt(size).putInt(size * 2);
        buffer.putShort((short) 1).putShort((short) 32);
        buffer.putInt(0).putInt(size * size * 4).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = size - 1; y >= 0; y--) {
            for (int x = 0; x < size; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DataOutputStream(out).write(buffer.array());
        return out.toByteArray();
    }
}
