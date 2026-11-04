package servidor;

import comum.Log;
import comum.Protocolo;
import comum.Visual;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * SERVIDOR com interface grafica (Swing).
 *
 * O servidor e so intermediario: a unica interacao e digitar a porta, iniciar e encerrar.
 * Tudo o que ele recebe, responde e decide aparece no log da janela (e tambem no terminal).
 */
public final class ServidorGUI {

    private final JFrame janela = new JFrame("Servidor - Reserva de Salas");
    private final JTextField campoPorta = Visual.campo(6);
    private final JButton botaoIniciar = Visual.botao("Iniciar servidor", "primario");
    private final JButton botaoEncerrar = Visual.botao("Encerrar servidor", "perigo");
    private final JLabel status = new JLabel();
    private final JLabel ips = Visual.subtitulo(" ");
    private final Visual.PainelLog log = new Visual.PainelLog();
    private ServerSocket servidor;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Visual.aplicarTema();
            new ServidorGUI().mostrar();
        });
    }

    private void mostrar() {
        Log.definirOuvinte(this::registrarLinha);

        JPanel raiz = new JPanel(new BorderLayout(0, 14));
        raiz.setBackground(Visual.FUNDO);
        raiz.setBorder(Visual.margem(18, 22));
        raiz.add(montarTopo(), BorderLayout.NORTH);
        raiz.add(montarLog(), BorderLayout.CENTER);

        janela.setContentPane(raiz);
        janela.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        janela.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { encerrar(); }
        });
        janela.setMinimumSize(new Dimension(760, 520));
        janela.setSize(1040, 700);
        janela.setLocationRelativeTo(null);
        janela.setVisible(true);
        campoPorta.requestFocusInWindow();
    }

    private JPanel montarTopo() {
        JPanel topo = new JPanel(new BorderLayout(0, 14));
        topo.setOpaque(false);

        JPanel cabecalho = new JPanel();
        cabecalho.setOpaque(false);
        cabecalho.setLayout(new BoxLayout(cabecalho, BoxLayout.Y_AXIS));
        cabecalho.add(Visual.titulo("Servidor"));
        cabecalho.add(Box.createVerticalStrut(2));
        cabecalho.add(Visual.subtitulo("Sistema de Reserva de Salas  -  protocolo v" + Protocolo.VERSAO
                + "  -  TCP, uma thread por cliente"));
        topo.add(cabecalho, BorderLayout.NORTH);

        JPanel cartao = Visual.cartao();
        JPanel linha = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        linha.setOpaque(false);
        linha.add(Visual.rotulo("Porta"));
        campoPorta.setText("5000");
        linha.add(campoPorta);
        linha.add(botaoIniciar);
        linha.add(Box.createHorizontalStrut(10));
        linha.add(status);
        cartao.add(linha, BorderLayout.NORTH);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(BorderFactory.createEmptyBorder(12, 2, 0, 0));
        info.add(ips);
        info.add(Box.createVerticalStrut(4));
        info.add(Visual.subtitulo("Admin inicial (regra 3.15): admin@admin.com  /  senha admin"));
        cartao.add(info, BorderLayout.CENTER);

        JPanel direita = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        direita.setOpaque(false);
        direita.add(botaoEncerrar);
        cartao.add(direita, BorderLayout.EAST);
        topo.add(cartao, BorderLayout.CENTER);

        definirStatus("Parado", Visual.TEXTO_SUAVE);
        botaoIniciar.addActionListener(e -> iniciar());
        campoPorta.addActionListener(e -> iniciar());
        botaoEncerrar.addActionListener(e -> encerrar());
        return topo;
    }

    private JPanel montarLog() {
        JPanel painel = new JPanel(new BorderLayout(0, 8));
        painel.setOpaque(false);

        JPanel barra = new JPanel(new BorderLayout());
        barra.setOpaque(false);
        barra.add(Visual.rotulo("Mensagens do servidor"), BorderLayout.WEST);

        JPanel legenda = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        legenda.setOpaque(false);
        legenda.add(item("recebido", new Color(0x2563EB)));
        legenda.add(item("enviado OK", Visual.SUCESSO));
        legenda.add(item("enviado com erro", Visual.ERRO));
        legenda.add(item("decisao", new Color(0x7C3AED)));
        JButton limpar = Visual.botao("Limpar", "secundario");
        limpar.addActionListener(e -> log.limpar());
        legenda.add(limpar);
        barra.add(legenda, BorderLayout.EAST);

        painel.add(barra, BorderLayout.NORTH);
        painel.add(Visual.rolagem(log), BorderLayout.CENTER);
        return painel;
    }

    private static JLabel item(String texto, Color cor) {
        JLabel l = new JLabel("\u25CF " + texto);
        l.setFont(Visual.FONTE_SUB);
        l.setForeground(cor);
        return l;
    }

    private void iniciar() {
        if (servidor != null) return;
        int porta;
        try {
            porta = Integer.parseInt(campoPorta.getText().trim());
            if (porta < 1 || porta > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            definirStatus("Porta invalida: digite um numero entre 1 e 65535", Visual.ERRO);
            return;
        }
        try {
            servidor = new ServerSocket(porta);
        } catch (IOException e) {
            definirStatus("Nao foi possivel abrir a porta " + porta + " (em uso?)", Visual.ERRO);
            Log.print("[SERVIDOR]", "ERRO: nao foi possivel abrir a porta " + porta + " (" + e.getMessage() + ")");
            return;
        }
        BancoDados banco = Servidor.criarBanco();
        campoPorta.setEnabled(false);
        botaoIniciar.setEnabled(false);
        definirStatus("\u25CF  Escutando na porta " + porta, Visual.SUCESSO);

        List<String> lista = Servidor.ipsDaMaquina();
        StringBuilder sb = new StringBuilder("<html>IP para os clientes: ");
        if (lista.isEmpty()) {
            sb.append("nenhuma rede encontrada");
        } else {
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) sb.append("  &nbsp;|&nbsp;  ");
                sb.append("<b>").append(lista.get(i).split(" ")[0]).append("</b>");
            }
        }
        sb.append("  &nbsp;&nbsp;(na mesma maquina: 127.0.0.1)</html>");
        ips.setText(sb.toString());

        Log.print("[SERVIDOR]", "Servidor escutando na porta " + porta);
        for (String ip : lista) Log.print("[SERVIDOR]", "IP para os clientes: " + ip + "  porta " + porta);
        Log.print("[SERVIDOR]", "Admin inicial (regra 3.15): email admin@admin.com / senha admin");

        ServerSocket s = servidor;
        Thread aceitar = new Thread(() -> Servidor.atender(s, banco), "aceitar-conexoes");
        aceitar.setDaemon(true);
        aceitar.start();
    }

    private void encerrar() {
        if (servidor != null) {
            int r = JOptionPane.showConfirmDialog(janela,
                    "Encerrar o servidor? Os clientes serao desconectados e os dados em memoria serao perdidos.",
                    "Encerrar servidor", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
            Log.print("[SERVIDOR]", "Servidor encerrado pelo operador.");
        }
        System.exit(0);
    }

    private void definirStatus(String texto, Color cor) {
        status.setText(texto);
        status.setFont(Visual.FONTE_NEGRITO);
        status.setForeground(cor);
    }

    /** Cor de cada linha do log conforme o tipo. */
    private void registrarLinha(String linha) {
        Color cor;
        if (linha.contains("RECEBIDO <-")) {
            cor = Visual.LOG_ENVIADO;
        } else if (linha.contains("ENVIADO  ->")) {
            cor = linha.matches(".*\"status\": \"2\\d\\d\".*") ? Visual.LOG_RECEBIDO : Visual.LOG_ERRO;
        } else if (linha.contains("RECUSADO") || linha.contains("ERRO")) {
            cor = Visual.LOG_AVISO;
        } else if (linha.contains("   * ")) {
            cor = Visual.LOG_NOTA;
        } else {
            cor = Visual.LOG_INFO;
        }
        log.adicionar(linha, cor);
    }
}
