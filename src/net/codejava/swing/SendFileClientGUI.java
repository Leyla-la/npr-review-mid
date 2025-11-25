package net.codejava.swing;

import javax.swing.*;

/**
 * Stub placeholder - the real client GUI is `updatedmid2025unicast.ClientGUI`.
 */
public class SendFileClientGUI extends JFrame {
    public SendFileClientGUI() {
        super("Stub");
        JLabel lbl = new JLabel("Use updatedmid2025unicast.ClientGUI for sending files.");
        add(lbl);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        pack();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SendFileClientGUI().setVisible(true));
    }
}
