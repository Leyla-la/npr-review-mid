package net.codejava.swing;

import javax.swing.*;

/**
 * Stub placeholder - real GUI should be in updatedmid2025unicast package (ClientGUI / Server).
 */
public class SwingFileDownloadHTTP extends JFrame {
    public SwingFileDownloadHTTP() {
        super("Stub");
        JLabel lbl = new JLabel("This is a placeholder. Use updatedmid2025unicast.ClientGUI and Server.");
        add(lbl);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        pack();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SwingFileDownloadHTTP().setVisible(true));
    }
}
