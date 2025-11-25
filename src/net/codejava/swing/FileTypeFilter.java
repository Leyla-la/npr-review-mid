package net.codejava.swing;

import java.io.File;

import javax.swing.filechooser.FileFilter;

public class FileTypeFilter extends FileFilter {
    @Override
    public boolean accept(File f) { return true; }

    @Override
    public String getDescription() { return "All files"; }
}
