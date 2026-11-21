import com.hamza.icons.IconSet;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Draws the application icon and writes it in the formats the build needs:
 * src/main/resources/icon-{16,32,48,256}.png (window and tray), packaging/app.ico (Windows
 * installer and .exe), packaging/app.png (Linux) and packaging/app.icns (macOS). The results are
 * committed; run this only to change the icon.
 *
 * <p>Only the drawing is here; the files are written by the app-icons library the programs share
 * (github.com/MohamedSalah-13/app-icons), a test-scope dependency in pom.xml, so Maven has already
 * downloaded its jar. From the desktop folder:
 *
 * <pre>java -cp "%USERPROFILE%\.m2\repository\com\github\MohamedSalah-13\app-icons\v0.1.0\app-icons-v0.1.0.jar" packaging\MakeIcon.java .</pre>
 */
public class MakeIcon {
    private static final int[] PNG_SIZES = {16, 32, 48, 256};

    public static void main(String[] args) throws IOException {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        IconSet.of(MakeIcon::draw)
                .pngs("src/main/resources/icon-%d.png", PNG_SIZES)
                .ico("packaging/app.ico")
                .png("packaging/app.png", 512)
                .icns("packaging/app.icns")
                .write(project);
        System.out.println("Icon files written under " + project);
    }

    /** A blue rounded square with a white arrow dropping into a tray, in pixels of the size asked for. */
    private static void draw(Graphics2D g, int size) {
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
    }
}
