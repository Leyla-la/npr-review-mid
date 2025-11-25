package net.codejava.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;

/**
 * Simple Swing client GUI to send a file to the server using the unicast protocol.
 * Protocol used:
 *  - send Student_ID as a line (optional, leave blank to skip)
 *  - send a command line "FILE"
 *  - send filename via DataOutputStream.writeUTF(filename)
 *  - send file size via DataOutputStream.writeLong(size)
 *  - send raw bytes
 */
public class SendFileClientGUI extends JFrame {
    private JTextField tfServer = new JTextField("localhost", 15);
    private JTextField tfPort = new JTextField("4075", 6);
    private JTextField tfStudentId = new JTextField(12);
    private JTextField tfFilePath = new JTextField(30);
    private JButton btnBrowse = new JButton("Browse...");
    private JButton btnSend = new JButton("Send File");
    private JProgressBar progressBar = new JProgressBar(0, 100);
    private JLabel lblStatus = new JLabel("Idle");

    public SendFileClientGUI() {
        super("Send File Client");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6,6,6,6);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        add(new JLabel("Server:"), c);
        c.gridx = 1; add(tfServer, c);
        c.gridx = 2; add(new JLabel("Port:"), c);
        c.gridx = 3; add(tfPort, c);

        c.gridx = 0; c.gridy = 1;
        add(new JLabel("Student ID (opt):"), c);
        c.gridx = 1; c.gridwidth = 3; add(tfStudentId, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 2;
        add(new JLabel("File:"), c);
        c.gridx = 1; c.gridwidth = 2; add(tfFilePath, c);
        c.gridx = 3; c.gridwidth = 1; add(btnBrowse, c);

        c.gridx = 0; c.gridy = 3; add(new JLabel("Progress:"), c);
        c.gridx = 1; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; add(progressBar, c);
        c.fill = GridBagConstraints.NONE; c.gridwidth = 1;

        c.gridx = 0; c.gridy = 4; add(new JLabel("Status:"), c);
        c.gridx = 1; c.gridwidth = 3; add(lblStatus, c);

        c.gridx = 1; c.gridy = 5; c.gridwidth = 1; add(btnSend, c);

        pack();
        setLocationRelativeTo(null);

        btnBrowse.addActionListener(this::onBrowse);
        btnSend.addActionListener(this::onSend);
    }

    private void onBrowse(ActionEvent ev) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            tfFilePath.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void onSend(ActionEvent ev) {
        String path = tfFilePath.getText().trim();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a file to send", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            JOptionPane.showMessageDialog(this, "Selected file does not exist", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String host = tfServer.getText().trim();
        int port;
        try {
            port = Integer.parseInt(tfPort.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        btnSend.setEnabled(false);
        progressBar.setValue(0);
        lblStatus.setText("Connecting...");

        // Run transfer in background
        new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (Socket socket = new Socket(host, port);
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                     DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
                     FileInputStream fis = new FileInputStream(file)) {

                    // Optionally send student id first
                    String sid = tfStudentId.getText().trim();
                    if (!sid.isEmpty()) {
                        writer.write(sid);
                        writer.newLine();
                        writer.flush();
                    }

                    // send command
                    writer.write("FILE");
                    writer.newLine();
                    writer.flush();

                    String fileName = file.getName();
                    long fileSize = file.length();

                    dataOut.writeUTF(fileName);
                    dataOut.writeLong(fileSize);
                    dataOut.flush();

                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        dataOut.write(buffer, 0, read);
                        total += read;
                        int progress = (int) ((total * 100) / fileSize);
                        setProgress(progress);
                        publish(progress);
                    }
                    dataOut.flush();

                    // read server response (if any)
                    InputStream in = socket.getInputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(in));
                    String response = null;
                    // try to read a UTF response if server used DataOutputStream.writeUTF
                    try {
                        socket.setSoTimeout(2000);
                        response = br.readLine();
                    } catch (IOException ignored) {}

                    final String resp = response;
                    SwingUtilities.invokeLater(() -> {
                        if (resp != null) lblStatus.setText("Server: " + resp);
                        else lblStatus.setText("File sent, no response.");
                    });

                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(SendFileClientGUI.this,
                            "Error sending file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                    SwingUtilities.invokeLater(() -> lblStatus.setText("Error: " + e.getMessage()));
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                int last = chunks.get(chunks.size() - 1);
                progressBar.setValue(last);
            }

            @Override
            protected void done() {
                btnSend.setEnabled(true);
                setProgress(100);
                progressBar.setValue(100);
            }
        }.execute();
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new SendFileClientGUI().setVisible(true));
    }
}

