package com.cleanroommc.unified;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;

/**
 * A small progress window for the one-time setup steps (unpacking, downloading Java), so the player is not left
 * staring at a launcher that seems to hang. Only messages are logged on servers and headless machines.
 */
final class StatusWindow implements AutoCloseable {

    private final boolean gui;
    private JFrame frame;
    private JLabel label;
    private JProgressBar bar;

    StatusWindow(boolean client) {
        this.gui = client && !GraphicsEnvironment.isHeadless();
    }

    void show(String message) {
        UnifiedLog.info(message);
        if (!gui) {
            return;
        }
        onEdt(() -> {
            if (frame == null) {
                create();
            }
            label.setText(message);
            bar.setIndeterminate(true);
        });
    }

    void progress(int done, int total) {
        if (!gui) {
            return;
        }
        onEdt(() -> {
            if (bar != null && total > 0) {
                bar.setIndeterminate(false);
                bar.setMaximum(total);
                bar.setValue(done);
            }
        });
    }

    void error(String message) {
        UnifiedLog.warn(message);
        if (!gui) {
            return;
        }
        close();
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, message, "Cleanroom", JOptionPane.ERROR_MESSAGE));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        if (!gui) {
            return;
        }
        onEdt(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
    }

    private void create() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        frame = new JFrame("Cleanroom");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        label = new JLabel();
        bar = new JProgressBar();
        bar.setPreferredSize(new Dimension(360, 18));
        panel.add(new JLabel("Preparing Cleanroom (first launch only)"), BorderLayout.NORTH);
        panel.add(label, BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void onEdt(Runnable r) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                SwingUtilities.invokeAndWait(r);
            }
        } catch (Exception e) {
            UnifiedLog.error("Status window failed", e);
        }
    }
}
