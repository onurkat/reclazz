package com.example.reclazz.uitest;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.text.BadLocationException;

final class LayoutProbe {
    private LayoutProbe() { }

    static String wizard(JDialog dialog, JScrollPane page) {
        var viewport = page.getViewport();
        if (page.getVerticalScrollBar().isShowing() || page.getHorizontalScrollBar().isShowing()) {
            return "page scrollbar: viewport=" + viewport.getExtentSize() + " view=" + viewport.getViewSize();
        }
        return check(dialog.getContentPane());
    }

    static String check(Component component) {
        if (!component.isShowing()) return "fits";
        // The Ant console deliberately scrolls its unwrapped output within a capped viewport.
        if (component instanceof JScrollPane scroll && scroll.getViewport().getView() instanceof JTextArea area
                && !area.getLineWrap()) return "fits";
        if (component instanceof JComponent c) {
            String text = null;
            if (c instanceof AbstractButton b) text = b.getText();
            if (c instanceof JLabel l) text = l.getText();
            boolean wrapped = c instanceof JEditorPane || c instanceof JTextArea area && area.getLineWrap();
            if (wrapped) text = ((JTextComponent)c).getText();
            if ((text != null && !text.isBlank()) || c instanceof AbstractButton && c.isFocusable()) {
                Rectangle bounds = new Rectangle(c.getSize());
                Rectangle visible = c.getVisibleRect();
                String problem = null;
                if (bounds.isEmpty() || !bounds.equals(visible)) problem = "partly outside visible ancestors";
                else if (wrapped) {
                    JTextComponent field = (JTextComponent)c;
                    try {
                        // A final caret alone misses a wide token on an earlier line.
                        for (int offset = 0; offset <= field.getDocument().getLength(); offset++) {
                            var caret = field.modelToView2D(offset);
                            if (caret != null && (caret.getMinX() < visible.x || caret.getMinX() > visible.getMaxX()
                                    || caret.getMinY() < visible.y || caret.getMaxY() > visible.getMaxY())) {
                                problem = "wrapped text clipped at offset " + offset + " caret=" + caret;
                                break;
                            }
                        }
                    } catch (BadLocationException ex) {
                        throw new IllegalStateException(ex);
                    }
                } else if (c instanceof JLabel || c instanceof AbstractButton) {
                    if (c.getPreferredSize().width > c.getWidth() || c.getPreferredSize().height > c.getHeight())
                        problem = "text or icon clipped";
                }
                if (problem != null) {
                    String label = text == null ? "" : text.replaceAll("\\s+", " ").strip();
                    return problem + ": " + c.getClass().getSimpleName() + " " + label.substring(0, Math.min(85, label.length()))
                            + " bounds=" + bounds + " visible=" + visible + " preferred=" + c.getPreferredSize();
                }
            }
        }
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                String result = check(child);
                if (!result.equals("fits")) return result;
            }
        }
        return "fits";
    }
}
