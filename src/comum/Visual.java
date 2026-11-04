package comum;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Cores, fontes e componentes com a mesma aparencia no cliente e no servidor. */
public final class Visual {

    private Visual() { }

    // ------------------------------------------------------------------ paleta
    public static final Color FUNDO        = new Color(0xF4F6F9);
    public static final Color CARTAO       = Color.WHITE;
    public static final Color BORDA        = new Color(0xDDE2E8);
    public static final Color TEXTO        = new Color(0x1F2933);
    public static final Color TEXTO_SUAVE  = new Color(0x6B7785);
    public static final Color LATERAL      = new Color(0x1E2A38);
    public static final Color LATERAL_ITEM = new Color(0x2C3B4D);
    public static final Color DESTAQUE     = new Color(0x2563EB);
    public static final Color SUCESSO      = new Color(0x15803D);
    public static final Color SUCESSO_FUNDO = new Color(0xE7F6EC);
    public static final Color ERRO         = new Color(0xB42318);
    public static final Color ERRO_FUNDO   = new Color(0xFDECEA);
    public static final Color AVISO        = new Color(0xB45309);
    public static final Color AVISO_FUNDO  = new Color(0xFFF4E0);
    public static final Color LINHA_ZEBRA  = new Color(0xF8FAFC);
    public static final Color SELECAO      = new Color(0xDBE7FE);

    private static final String FAMILIA = escolherFonte();
    public static final Font FONTE        = new Font(FAMILIA, Font.PLAIN, 13);
    public static final Font FONTE_NEGRITO = new Font(FAMILIA, Font.BOLD, 13);
    public static final Font FONTE_TITULO = new Font(FAMILIA, Font.BOLD, 20);
    public static final Font FONTE_SUB    = new Font(FAMILIA, Font.PLAIN, 12);
    public static final Font FONTE_MONO   = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private static String escolherFonte() {
        String[] preferidas = {"Segoe UI", "SF Pro Text", "Helvetica Neue", "Ubuntu", "DejaVu Sans"};
        java.util.Set<String> disponiveis = new java.util.HashSet<>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : preferidas) {
            if (disponiveis.contains(f)) return f;
        }
        return Font.SANS_SERIF;
    }

    /** Chamar antes de criar qualquer janela. */
    public static void aplicarTema() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignorada) {
            // fica o padrao do Java
        }
        for (String chave : new String[] {"Label.font", "TextField.font", "PasswordField.font", "ComboBox.font",
            "Table.font", "TableHeader.font", "OptionPane.messageFont", "OptionPane.buttonFont", "Button.font",
            "List.font", "CheckBox.font", "TextArea.font", "ToolTip.font"}) {
            UIManager.put(chave, FONTE);
        }
        UIManager.put("Panel.background", FUNDO);
        UIManager.put("OptionPane.background", CARTAO);
    }

    // ------------------------------------------------------------------ componentes

    /** Botao plano e arredondado. tipo: "primario", "secundario", "perigo", "lateral". */
    public static JButton botao(String texto, String tipo) {
        return new Botao(texto, tipo);
    }

    private static final class Botao extends JButton {
        private static final long serialVersionUID = 1L;
        private final String tipo;
        private boolean sobre;
        private boolean ativo; // item selecionado na barra lateral

        Botao(String texto, String tipo) {
            super(texto);
            this.tipo = tipo;
            setFont("lateral".equals(tipo) ? FONTE : FONTE_NEGRITO);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
            if ("lateral".equals(tipo)) {
                setHorizontalAlignment(LEFT);
                setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 12));
            }
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { sobre = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { sobre = false; repaint(); }
            });
            putClientProperty("visual.botao", this);
        }

        void definirAtivo(boolean a) {
            ativo = a;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fundo;
            Color texto;
            Color borda = null;
            switch (tipo) {
                case "primario":
                    fundo = isEnabled() ? (sobre ? DESTAQUE.darker() : DESTAQUE) : new Color(0xA7B6D6);
                    texto = Color.WHITE;
                    break;
                case "perigo":
                    fundo = isEnabled() ? (sobre ? ERRO_FUNDO.darker() : ERRO_FUNDO) : FUNDO;
                    texto = isEnabled() ? ERRO : TEXTO_SUAVE;
                    break;
                case "lateral":
                    fundo = ativo ? DESTAQUE : (sobre ? LATERAL_ITEM : LATERAL);
                    texto = ativo || sobre ? Color.WHITE : new Color(0xC9D3DE);
                    break;
                default:
                    fundo = isEnabled() ? (sobre ? new Color(0xEEF1F5) : CARTAO) : FUNDO;
                    texto = isEnabled() ? TEXTO : TEXTO_SUAVE;
                    borda = BORDA;
            }
            g2.setColor(fundo);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            if (borda != null) {
                g2.setColor(borda);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
            g2.dispose();
            setForeground(texto);
            super.paintComponent(g);
        }
    }

    /** Marca um botao da barra lateral como a tela atual. */
    public static void marcarAtivo(JButton b, boolean ativo) {
        if (b instanceof Botao) ((Botao) b).definirAtivo(ativo);
    }

    public static JTextField campo(int colunas) {
        JTextField f = new JTextField(colunas);
        estilizarCampo(f);
        return f;
    }

    public static void estilizarCampo(JComponent c) {
        c.setFont(FONTE);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDA), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
    }

    public static JLabel titulo(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(FONTE_TITULO);
        l.setForeground(TEXTO);
        return l;
    }

    public static JLabel subtitulo(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(FONTE_SUB);
        l.setForeground(TEXTO_SUAVE);
        return l;
    }

    public static JLabel rotulo(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(FONTE_NEGRITO);
        l.setForeground(TEXTO);
        return l;
    }

    /** Painel branco com borda fina e margem interna. */
    public static JPanel cartao() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(CARTAO);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDA), BorderFactory.createEmptyBorder(18, 20, 18, 20)));
        return p;
    }

    public static Border margem(int v, int h) {
        return BorderFactory.createEmptyBorder(v, h, v, h);
    }

    /**
     * Formulario simples: rotulo em cima, campo embaixo, dica opcional em cinza.
     * linhas: { rotulo, componente, dica } (dica pode ser null).
     */
    public static JPanel formulario(Object[][] linhas) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        int y = 0;
        for (Object[] l : linhas) {
            c.gridy = y++;
            c.insets = new Insets(y == 1 ? 0 : 10, 0, 4, 0);
            p.add(rotulo((String) l[0]), c);
            c.gridy = y++;
            c.insets = new Insets(0, 0, 0, 0);
            p.add((Component) l[1], c);
            if (l.length > 2 && l[2] != null) {
                c.gridy = y++;
                c.insets = new Insets(3, 0, 0, 0);
                p.add(subtitulo((String) l[2]), c);
            }
        }
        return p;
    }

    /** Tabela limpa: linhas mais altas, zebrada, sem grade vertical. */
    public static JTable tabela(javax.swing.table.TableModel modelo) {
        JTable t = new JTable(modelo);
        t.setFont(FONTE);
        t.setRowHeight(28);
        t.setShowVerticalLines(false);
        t.setGridColor(new Color(0xEEF1F4));
        t.setSelectionBackground(SELECAO);
        t.setSelectionForeground(TEXTO);
        t.setFillsViewportHeight(true);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable tab, Object v, boolean sel, boolean foco, int l, int col) {
                super.getTableCellRendererComponent(tab, v, sel, false, l, col);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                if (!sel) setBackground(l % 2 == 0 ? CARTAO : LINHA_ZEBRA);
                return this;
            }
        });
        JTableHeader h = t.getTableHeader();
        h.setFont(FONTE_NEGRITO);
        h.setReorderingAllowed(false);
        h.setDefaultRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable tab, Object v, boolean sel, boolean foco, int l, int col) {
                super.getTableCellRendererComponent(tab, v, false, false, l, col);
                setFont(FONTE_NEGRITO);
                setForeground(TEXTO_SUAVE);
                setBackground(FUNDO);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, BORDA), BorderFactory.createEmptyBorder(7, 10, 7, 10)));
                return this;
            }
        });
        return t;
    }

    public static JScrollPane rolagem(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(BorderFactory.createLineBorder(BORDA));
        s.getViewport().setBackground(CARTAO);
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    // ------------------------------------------------------------------ painel de log colorido

    /** Area de log somente leitura, monoespacada, com cor por linha. */
    public static final class PainelLog extends JTextPane {
        private static final long serialVersionUID = 1L;
        private static final int LIMITE = 400_000; // caracteres guardados

        public PainelLog() {
            setEditable(false);
            setFont(FONTE_MONO);
            setBackground(new Color(0x111827));
            setForeground(new Color(0xE5E7EB));
            setCaretColor(Color.WHITE);
            setBorder(margem(8, 10));
        }

        /** Pode ser chamado de qualquer thread. */
        public void adicionar(String linha, Color cor) {
            SwingUtilities.invokeLater(() -> {
                StyledDocument doc = getStyledDocument();
                SimpleAttributeSet a = new SimpleAttributeSet();
                StyleConstants.setForeground(a, cor);
                try {
                    doc.insertString(doc.getLength(), linha + "\n", a);
                    if (doc.getLength() > LIMITE) doc.remove(0, doc.getLength() - LIMITE);
                } catch (BadLocationException ignorada) {
                    // nao acontece
                }
                setCaretPosition(doc.getLength());
            });
        }

        public void limpar() {
            setText("");
        }
    }

    // cores do log (fundo escuro)
    public static final Color LOG_ENVIADO  = new Color(0x93C5FD);
    public static final Color LOG_RECEBIDO = new Color(0x86EFAC);
    public static final Color LOG_ERRO     = new Color(0xFCA5A5);
    public static final Color LOG_AVISO    = new Color(0xFCD34D);
    public static final Color LOG_INFO     = new Color(0xCBD5E1);
    public static final Color LOG_NOTA     = new Color(0xC4B5FD);
}
