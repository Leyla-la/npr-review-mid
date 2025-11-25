package net.codejava.swing;

import javax.swing.*;

/**
 * Minimal stub for JFilePicker (not used by the main project per user's requirement).
 */
public class JFilePicker extends JPanel {
    public static final int MODE_OPEN = 1;
    public static final int MODE_SAVE = 2;

    public JFilePicker(String textFieldLabel, String buttonLabel) {
        // stub
    }

    public void setMode(int mode) {}
    public void addFileTypeFilter(String extension, String description) {}
    public String getSelectedFilePath() { return ""; }
    public JFileChooser getFileChooser() { return new JFileChooser(); }
}
