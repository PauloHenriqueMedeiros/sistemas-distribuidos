package cliente;

import comum.Log;
import comum.Protocolo;
import comum.Visual;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

/**
 * CLIENTE com interface grafica (Swing).
 *
 * Faz tudo o que o cliente de terminal faz. Toda mensagem enviada e recebida aparece no painel
 * "Mensagens" (e tambem no terminal), e o resultado de cada operacao aparece na faixa colorida do topo.
 * As requisicoes rodam fora da thread da tela, entao a janela nunca trava esperando o servidor.
 */
public final class ClienteGUI {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JFrame janela = new JFrame("Cliente - Reserva de Salas");
    private final CardLayout cartoesRaiz = new CardLayout();
    private final JPanel raiz = new JPanel(cartoesRaiz);
    private final Visual.PainelLog log = new Visual.PainelLog();
    private final Rede rede = new Rede(this::eventoRede);

    // sessao (o token fica so em memoria, regra 3.2; o role do login so monta a tela, regra 3.14)
    private String token;
    private String role;
    private String emailLogado;

    // area principal
    private final JPanel lateral = new JPanel();
    private final CardLayout cartoesTelas = new CardLayout();
    private final JPanel telas = new JPanel(cartoesTelas);
    private final JPanel faixa = new JPanel(new BorderLayout());
    private final JLabel faixaTexto = new JLabel();
    private final Map<String, JButton> botoesMenu = new LinkedHashMap<>();
    private final Map<String, Runnable> aoAbrir = new LinkedHashMap<>();
    private String telaAtual;
    private boolean ocupado;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Visual.aplicarTema();
            new ClienteGUI().mostrar();
        });
    }

    private void mostrar() {
        raiz.add(montarConexao(), "conexao");
        raiz.add(montarAplicacao(), "app");
        janela.setContentPane(raiz);
        janela.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        janela.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { sair(); }
        });
        // trava cliques enquanto uma requisicao esta em andamento
        janela.getGlassPane().addMouseListener(new MouseAdapter() { });
        janela.getGlassPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        janela.setMinimumSize(new Dimension(980, 640));
        janela.setSize(1240, 800);
        janela.setLocationRelativeTo(null);
        janela.setVisible(true);
    }

    // =====================================================================
    // TELA DE CONEXAO (campo de IP e campo de porta, obs. 5)
    // =====================================================================

    private JPanel montarConexao() {
        JTextField ip = Visual.campo(18);
        ip.setText("127.0.0.1");
        JTextField porta = Visual.campo(18);
        porta.setText("5000");
        JLabel erro = new JLabel(" ");
        erro.setForeground(Visual.ERRO);
        erro.setFont(Visual.FONTE_SUB);
        JButton conectar = Visual.botao("Conectar", "primario");

        JPanel form = Visual.formulario(new Object[][] {
            {"IP do servidor", ip, null},
            {"Porta", porta, null},
        });
        JPanel cartao = Visual.cartao();
        cartao.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Visual.BORDA), Visual.margem(28, 32)));
        JPanel conteudo = coluna();
        conteudo.add(esquerda(Visual.titulo("Reserva de Salas")));
        conteudo.add(esquerda(Visual.subtitulo("Cliente - protocolo v" + Protocolo.VERSAO)));
        conteudo.add(Box.createVerticalStrut(22));
        conteudo.add(esquerda(form));
        conteudo.add(Box.createVerticalStrut(18));
        JPanel botoes = new JPanel(new BorderLayout());
        botoes.setOpaque(false);
        botoes.add(conectar, BorderLayout.CENTER);
        conteudo.add(esquerda(botoes));
        conteudo.add(Box.createVerticalStrut(10));
        conteudo.add(esquerda(erro));
        cartao.add(conteudo, BorderLayout.CENTER);

        Runnable acao = () -> {
            int p;
            try {
                p = Integer.parseInt(porta.getText().trim());
                if (p < 1 || p > 65535) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                erro.setText("Porta invalida: digite um numero entre 1 e 65535.");
                return;
            }
            String endereco = ip.getText().trim().isEmpty() ? "127.0.0.1" : ip.getText().trim();
            conectar.setEnabled(false);
            erro.setText("Conectando...");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() { return rede.conectar(endereco, p); }
                @Override protected void done() {
                    conectar.setEnabled(true);
                    String falha;
                    try {
                        falha = get();
                    } catch (Exception ex) {
                        falha = ex.getMessage();
                    }
                    if (falha != null) {
                        erro.setText("<html>" + falha + "</html>");
                        return;
                    }
                    erro.setText(" ");
                    reconstruirMenu();
                    cartoesRaiz.show(raiz, "app");
                    abrir("login");
                }
            }.execute();
        };
        conectar.addActionListener(e -> acao.run());
        ip.addActionListener(e -> acao.run());
        porta.addActionListener(e -> acao.run());

        JPanel fundo = new JPanel(new GridBagLayout());
        fundo.setBackground(Visual.FUNDO);
        fundo.add(larguraFixa(cartao, 400));
        return fundo;
    }

    // =====================================================================
    // ESTRUTURA PRINCIPAL: menu lateral | (faixa de resultado + tela) / mensagens
    // =====================================================================

    private JPanel montarAplicacao() {
        lateral.setBackground(Visual.LATERAL);
        lateral.setLayout(new BorderLayout());
        lateral.setPreferredSize(new Dimension(230, 100));

        faixa.setBorder(Visual.margem(10, 14));
        faixaTexto.setFont(Visual.FONTE_NEGRITO);
        faixa.add(faixaTexto, BorderLayout.CENTER);
        faixa.setVisible(false);

        telas.setOpaque(false);
        registrarTela("login", telaLogin(), null);
        registrarTela("cadastro", telaCadastro(), null);

        JPanel centro = new JPanel(new BorderLayout(0, 12));
        centro.setBackground(Visual.FUNDO);
        centro.setBorder(Visual.margem(18, 24));
        centro.add(faixa, BorderLayout.NORTH);
        centro.add(telas, BorderLayout.CENTER);

        JPanel painelLog = new JPanel(new BorderLayout(0, 6));
        painelLog.setBackground(Visual.FUNDO);
        painelLog.setBorder(BorderFactory.createEmptyBorder(8, 24, 14, 24));
        JPanel barraLog = new JPanel(new BorderLayout());
        barraLog.setOpaque(false);
        barraLog.add(Visual.rotulo("Mensagens enviadas e recebidas"), BorderLayout.WEST);
        JButton limpar = Visual.botao("Limpar", "secundario");
        limpar.addActionListener(e -> log.limpar());
        barraLog.add(limpar, BorderLayout.EAST);
        painelLog.add(barraLog, BorderLayout.NORTH);
        painelLog.add(Visual.rolagem(log), BorderLayout.CENTER);

        JSplitPane divisao = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centro, painelLog);
        divisao.setResizeWeight(0.7);
        divisao.setBorder(null);
        divisao.setDividerSize(6);
        divisao.setContinuousLayout(true);

        JPanel app = new JPanel(new BorderLayout());
        app.add(lateral, BorderLayout.WEST);
        app.add(divisao, BorderLayout.CENTER);
        return app;
    }

    private void registrarTela(String nome, JComponent tela, Runnable aoMostrar) {
        telas.add(tela, nome);
        if (aoMostrar != null) aoAbrir.put(nome, aoMostrar);
    }

    /** O menu muda conforme o estado: sem login, logado ou admin. */
    private void reconstruirMenu() {
        lateral.removeAll();
        botoesMenu.clear();

        JPanel topo = coluna();
        topo.setBorder(BorderFactory.createEmptyBorder(20, 18, 14, 18));
        JLabel nome = new JLabel("Reserva de Salas");
        nome.setFont(Visual.FONTE_NEGRITO.deriveFont(15f));
        nome.setForeground(Color.WHITE);
        topo.add(esquerda(nome));
        JLabel servidor = new JLabel("\u25CF  " + rede.ip() + ":" + rede.porta());
        servidor.setFont(Visual.FONTE_SUB);
        servidor.setForeground(new Color(0x86EFAC));
        topo.add(Box.createVerticalStrut(4));
        topo.add(esquerda(servidor));

        JPanel itens = coluna();
        if (token == null) {
            secao(itens, "CONTA");
            itemMenu(itens, "login", "Entrar");
            itemMenu(itens, "cadastro", "Criar conta");
        } else {
            secao(itens, "MINHA CONTA");
            itemMenu(itens, "meu", "Meu cadastro");
            secao(itens, "SALAS");
            itemMenu(itens, "salas", "Salas");
            itemMenu(itens, "reservar", "Reservar sala");
            itemMenu(itens, "minhas", "Minhas reservas");
            if ("admin".equals(role)) {
                secao(itens, "ADMINISTRADOR");
                itemMenu(itens, "usuarios", "Usuarios");
                itemMenu(itens, "admsalas", "Gerenciar salas");
            }
        }

        JPanel rodape = coluna();
        rodape.setBorder(BorderFactory.createEmptyBorder(12, 14, 16, 14));
        if (token != null) {
            JLabel quem = new JLabel(emailLogado == null ? "" : emailLogado);
            quem.setForeground(Color.WHITE);
            quem.setFont(Visual.FONTE_NEGRITO);
            JLabel perfil = new JLabel("admin".equals(role) ? "Administrador" : "Usuario comum");
            perfil.setForeground(new Color(0xA9B6C4));
            perfil.setFont(Visual.FONTE_SUB);
            rodape.add(esquerda(quem));
            rodape.add(esquerda(perfil));
            rodape.add(Box.createVerticalStrut(10));
            JButton sair = Visual.botao("Sair da conta (logout)", "secundario");
            sair.addActionListener(e -> logout());
            rodape.add(esquerda(sair));
        }

        JPanel meio = new JPanel(new BorderLayout());
        meio.setOpaque(false);
        meio.add(itens, BorderLayout.NORTH);
        lateral.add(topo, BorderLayout.NORTH);
        lateral.add(meio, BorderLayout.CENTER);
        lateral.add(rodape, BorderLayout.SOUTH);
        lateral.revalidate();
        lateral.repaint();
        if (telaAtual != null) marcarMenu(telaAtual);
    }

    private void secao(JPanel p, String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(Visual.FONTE_SUB.deriveFont(java.awt.Font.BOLD, 11f));
        l.setForeground(new Color(0x7D8B9B));
        l.setBorder(BorderFactory.createEmptyBorder(16, 18, 6, 0));
        p.add(esquerda(l));
    }

    private void itemMenu(JPanel p, String tela, String texto) {
        JButton b = Visual.botao(texto, "lateral");
        b.addActionListener(e -> {
            if (ocupado) return;
            faixa.setVisible(false);
            abrir(tela);
        });
        botoesMenu.put(tela, b);
        JPanel linha = new JPanel(new BorderLayout());
        linha.setOpaque(false);
        linha.setBorder(BorderFactory.createEmptyBorder(1, 10, 1, 10));
        linha.add(b, BorderLayout.CENTER);
        linha.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height + 2));
        linha.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(linha);
    }

    private void marcarMenu(String tela) {
        for (Map.Entry<String, JButton> e : botoesMenu.entrySet()) {
            Visual.marcarAtivo(e.getValue(), e.getKey().equals(tela));
        }
    }

    /** Mostra a tela e roda a carga dela (ex.: listar salas manda list_rooms). */
    private void abrir(String tela) {
        if (ocupado) return;
        telaAtual = tela;
        cartoesTelas.show(telas, tela);
        marcarMenu(tela);
        Runnable carga = aoAbrir.get(tela);
        if (carga != null) carga.run();
    }

    // =====================================================================
    // ENVIO (fora da thread da tela) e EFEITOS DA RESPOSTA
    // =====================================================================

    private Map<String, Object> req(String op) {
        Map<String, Object> m = new LinkedHashMap<>(); // 'op' sempre como primeira chave
        m.put("op", op);
        if (token != null && !op.equals("login") && !op.equals("register")) m.put("token", token);
        return m;
    }

    /** Envia uma acao: o resultado sempre aparece na faixa. 'depois' recebe a resposta (pode ser null). */
    private void enviar(Map<String, Object> pedido, Consumer<Map<String, Object>> depois) {
        enviar(pedido, depois, true);
    }

    /** Carga de tela (listagens): a faixa so aparece se der erro, para nao esconder o resultado da ultima acao. */
    private void carregar(Map<String, Object> pedido, Consumer<Map<String, Object>> depois) {
        enviar(pedido, depois, false);
    }

    private void enviar(Map<String, Object> pedido, Consumer<Map<String, Object>> depois, boolean sempreMostrar) {
        if (ocupado) return;
        ocupado = true;
        janela.getGlassPane().setVisible(true);
        String tokenUsado = Rede.texto(pedido, "token");
        new SwingWorker<Map<String, Object>, Void>() {
            @Override protected Map<String, Object> doInBackground() { return rede.enviar(pedido); }
            @Override protected void done() {
                Map<String, Object> r = null;
                try {
                    r = get();
                } catch (Exception ex) {
                    eventoRede("ERRO", "Falha inesperada: " + ex);
                }
                ocupado = false;
                janela.getGlassPane().setVisible(false);
                if (sempreMostrar || !sucesso(r)) mostrarResultado(r);
                if (r != null) aplicarEfeitos(pedido, r, tokenUsado);
                if (depois != null) depois.accept(r);
            }
        }.execute();
    }

    private void enviar(Map<String, Object> pedido) {
        enviar(pedido, null);
    }

    private void mostrarResultado(Map<String, Object> r) {
        String status = r == null ? null : Rede.texto(r, "status");
        boolean ok = status != null && status.startsWith("2");
        String texto;
        if (r == null) {
            texto = "Sem resposta do servidor. Veja os detalhes nas mensagens abaixo.";
        } else {
            String tipo = "error".equals(Rede.texto(r, "op")) ? "Erro de protocolo" : ok ? "Sucesso" : "Falha";
            texto = tipo + "  [" + String.valueOf(r.get("status")) + "]   " + String.valueOf(r.get("message"));
        }
        faixa.setBackground(ok ? Visual.SUCESSO_FUNDO : Visual.ERRO_FUNDO);
        faixa.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, ok ? Visual.SUCESSO : Visual.ERRO), Visual.margem(10, 14)));
        faixaTexto.setForeground(ok ? Visual.SUCESSO : Visual.ERRO);
        faixaTexto.setText(texto);
        faixa.setVisible(true);
    }

    /** Mesmas regras do cliente de terminal (regras 3.9, 3.10 e 3.14). */
    private void aplicarEfeitos(Map<String, Object> pedido, Map<String, Object> r, String tokenUsado) {
        String op = (String) pedido.get("op");
        String status = Rede.texto(r, "status");
        if (status == null) return;
        boolean meuToken = token != null && token.equals(tokenUsado);

        if (op.equals("login") && status.equals("200") && Rede.texto(r, "token") != null) {
            token = Rede.texto(r, "token");
            role = Rede.texto(r, "role");
            eventoRede("INFO", "Token guardado em memoria. Logado como '" + role + "'.");
            reconstruirMenu();
            abrir("salas");
            return;
        }
        if ((op.equals("logout") || op.equals("delete_user")) && status.equals("200") && meuToken) {
            encerrarSessao("Token descartado. Voce nao esta mais logado.");
            return;
        }
        boolean opDeAdmin = op.startsWith("admin_") || op.equals("create_room") || op.equals("update_room")
                || op.equals("delete_room");
        if (status.equals("403") && meuToken && "admin".equals(role)
                && "Permissao insuficiente".equals(Rede.texto(r, "message"))
                && (opDeAdmin || "all".equals(pedido.get("scope")))) {
            role = "user";
            eventoRede("INFO", "O servidor nao reconhece este login como admin. As opcoes de admin foram escondidas.");
            reconstruirMenu();
            abrir("salas");
            return;
        }
        // Regra 3.9: 401 (fora do login) = o servidor nao tem sessao para este token
        if (status.equals("401") && !op.equals("login") && meuToken) {
            encerrarSessao("O servidor nao reconhece mais o token. Token descartado: faca login novamente.");
        }
    }

    private void encerrarSessao(String motivo) {
        token = null;
        role = null;
        emailLogado = null;
        eventoRede("INFO", motivo);
        reconstruirMenu();
        abrir("login");
    }

    /** Eventos da rede vao para o painel de mensagens e para o terminal. Pode vir de qualquer thread. */
    private void eventoRede(String tipo, String texto) {
        Color cor;
        String seta = "";
        switch (tipo) {
            case "ENVIADO":
                cor = Visual.LOG_ENVIADO;
                seta = "-> ";
                break;
            case "RECEBIDO":
                cor = texto.matches(".*\"status\"\\s*:\\s*\"2\\d\\d\".*") ? Visual.LOG_RECEBIDO : Visual.LOG_ERRO;
                seta = "<- ";
                break;
            case "AVISO":
                cor = Visual.LOG_AVISO;
                break;
            case "ERRO":
                cor = Visual.LOG_ERRO;
                break;
            default:
                cor = Visual.LOG_INFO;
        }
        Log.print("[" + tipo + "]", seta + texto);
        log.adicionar("[" + LocalTime.now().format(HORA) + "] " + String.format("%-9s", tipo) + " " + seta + texto, cor);
    }

    // =====================================================================
    // TELAS SEM LOGIN
    // =====================================================================

    private JComponent telaLogin() {
        JTextField email = Visual.campo(24);
        JPasswordField senha = new JPasswordField(24);
        Visual.estilizarCampo(senha);
        JButton entrar = Visual.botao("Entrar", "primario");
        JButton criar = Visual.botao("Criar uma conta", "secundario");

        Runnable acao = () -> {
            String e = email.getText().trim();
            String s = new String(senha.getPassword());
            conferir("email", e, Protocolo.EMAIL, false);
            conferir("password", s, Protocolo.PASSWORD, false);
            Map<String, Object> m = req("login");
            m.put("email", e);
            m.put("password", s);
            enviar(m, r -> {
                if (token != null) {
                    emailLogado = e;
                    senha.setText("");
                    reconstruirMenu();
                }
            });
        };
        entrar.addActionListener(ev -> acao.run());
        senha.addActionListener(ev -> acao.run());
        criar.addActionListener(ev -> abrir("cadastro"));
        telaLoginEmail = email;

        JPanel form = Visual.formulario(new Object[][] {
            {"Email", email, null},
            {"Senha", senha, null},
        });
        return paginaFormulario("Entrar", "Use o email e a senha cadastrados.\nAdmin inicial: admin@admin.com / admin",
                form, entrar, criar);
    }

    private JTextField telaLoginEmail;

    private JComponent telaCadastro() {
        JTextField email = Visual.campo(24);
        JTextField user = Visual.campo(24);
        JPasswordField senha = new JPasswordField(24);
        Visual.estilizarCampo(senha);
        JButton cadastrar = Visual.botao("Cadastrar", "primario");
        JButton voltar = Visual.botao("Ja tenho conta", "secundario");

        cadastrar.addActionListener(ev -> {
            String e = email.getText().trim();
            String u = user.getText().trim();
            String s = new String(senha.getPassword());
            conferir("email", e, Protocolo.EMAIL, false);
            conferir("user", u, Protocolo.USER, false);
            conferir("password", s, Protocolo.PASSWORD, false);
            Map<String, Object> m = req("register");
            m.put("email", e);
            m.put("user", u);
            m.put("password", s);
            enviar(m, r -> {
                if (sucesso(r)) {
                    telaLoginEmail.setText(e);
                    email.setText("");
                    user.setText("");
                    senha.setText("");
                    cartoesTelas.show(telas, "login");
                    telaAtual = "login";
                    marcarMenu("login");
                }
            });
        });
        voltar.addActionListener(ev -> abrir("login"));

        JPanel form = Visual.formulario(new Object[][] {
            {"Email", email, "Minusculas. Ex.: nome.sobrenome@email.com ou nome@alunos.utfpr.edu.br"},
            {"Usuario", user, "So letras minusculas, sem numeros nem espacos (1 a 30)"},
            {"Senha", senha, "Letras e numeros, sem espacos (1 a 20)"},
        });
        return paginaFormulario("Criar conta", "Toda conta nova nasce como usuario comum.", form, cadastrar, voltar);
    }

    // =====================================================================
    // MEU CADASTRO
    // =====================================================================

    private JLabel meuUser;
    private JLabel meuEmail;
    private JLabel meuPerfil;
    private JLabel meuCriado;

    private JComponent telaMeuCadastro() {
        meuUser = valor();
        meuEmail = valor();
        meuPerfil = valor();
        meuCriado = valor();
        JPanel dados = new JPanel(new GridLayout(2, 4, 18, 4));
        dados.setOpaque(false);
        dados.add(Visual.subtitulo("Usuario"));
        dados.add(Visual.subtitulo("Email"));
        dados.add(Visual.subtitulo("Perfil"));
        dados.add(Visual.subtitulo("Criado em"));
        dados.add(meuUser);
        dados.add(meuEmail);
        dados.add(meuPerfil);
        dados.add(meuCriado);
        JPanel cartaoDados = Visual.cartao();
        cartaoDados.add(dados, BorderLayout.CENTER);

        JTextField novoUser = Visual.campo(22);
        JPasswordField novaSenha = new JPasswordField(22);
        Visual.estilizarCampo(novaSenha);
        JButton salvar = Visual.botao("Salvar alteracoes", "primario");
        salvar.addActionListener(ev -> {
            String u = novoUser.getText().trim();
            String s = new String(novaSenha.getPassword());
            conferir("user", u, Protocolo.USER, true);
            conferir("password", s, Protocolo.PASSWORD, true);
            if (u.isEmpty() && s.isEmpty()) {
                eventoRede("AVISO", "Os dois campos estao vazios: o servidor deve responder 400 \"Nenhum dado para atualizar\". Enviando mesmo assim.");
            }
            Map<String, Object> m = req("update_user");
            m.put("user", u);
            m.put("password", s);
            enviar(m, r -> {
                if (sucesso(r)) {
                    novoUser.setText("");
                    novaSenha.setText("");
                    carregarMeuCadastro();
                }
            });
        });
        JPanel form = Visual.formulario(new Object[][] {
            {"Novo usuario", novoUser, "Deixe em branco para manter. O email nao pode ser alterado."},
            {"Nova senha", novaSenha, "Deixe em branco para manter."},
        });
        JPanel cartaoEditar = Visual.cartao();
        JPanel colEditar = coluna();
        colEditar.add(esquerda(Visual.rotulo("Alterar meus dados")));
        colEditar.add(Box.createVerticalStrut(12));
        colEditar.add(esquerda(limitar(form, 420)));
        colEditar.add(Box.createVerticalStrut(14));
        colEditar.add(esquerda(botaoFormulario(salvar)));
        cartaoEditar.add(colEditar, BorderLayout.CENTER);

        JButton apagar = Visual.botao("Apagar minha conta", "perigo");
        apagar.addActionListener(ev -> apagarMinhaConta());
        JPanel cartaoApagar = Visual.cartao();
        JPanel colApagar = coluna();
        colApagar.add(esquerda(Visual.rotulo("Apagar conta")));
        colApagar.add(Box.createVerticalStrut(4));
        colApagar.add(esquerda(Visual.subtitulo("Remove o cadastro, a sessao e todas as suas reservas. Pede a senha para confirmar.")));
        colApagar.add(Box.createVerticalStrut(12));
        colApagar.add(esquerda(botaoFormulario(apagar)));
        cartaoApagar.add(colApagar, BorderLayout.CENTER);

        JPanel corpo = coluna();
        corpo.add(esquerda(cartaoDados));
        corpo.add(Box.createVerticalStrut(14));
        corpo.add(esquerda(cartaoEditar));
        corpo.add(Box.createVerticalStrut(14));
        corpo.add(esquerda(cartaoApagar));
        return pagina("Meu cadastro", "Seus dados, lidos do servidor com read_user.", null, rolavel(corpo));
    }

    private void carregarMeuCadastro() {
        carregar(req("read_user"), r -> {
            if (!sucesso(r)) return;
            meuUser.setText(Rede.texto(r, "user"));
            meuEmail.setText(Rede.texto(r, "email"));
            meuPerfil.setText("admin".equals(Rede.texto(r, "role")) ? "Administrador" : "Usuario comum");
            meuCriado.setText(Rede.texto(r, "created_at"));
        });
    }

    private void apagarMinhaConta() {
        JPasswordField senha = new JPasswordField(18);
        Visual.estilizarCampo(senha);
        JPanel p = Visual.formulario(new Object[][] {
            {"Confirme sua senha", senha, "Esta acao nao pode ser desfeita."},
        });
        if (!dialogo("Apagar minha conta", p, "Apagar conta")) return;
        String s = new String(senha.getPassword());
        conferir("password", s, Protocolo.PASSWORD, false);
        Map<String, Object> m = req("delete_user");
        m.put("password", s);
        enviar(m);
    }

    // =====================================================================
    // SALAS (qualquer usuario logado)
    // =====================================================================

    private final DefaultTableModel modeloSalas = modelo("N", "Nome", "Capacidade", "Local", "Recursos", "Status", "No horario");
    private JTable tabelaSalas;

    private JComponent telaSalas() {
        JTextField data = Visual.campo(10);
        JTextField ini = Visual.campo(6);
        JTextField fim = Visual.campo(6);
        JTextField minimo = Visual.campo(6);
        JButton buscar = Visual.botao("Buscar", "primario");
        JButton limpar = Visual.botao("Limpar filtros", "secundario");

        JPanel filtros = new JPanel(new GridBagLayout());
        filtros.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.SOUTHWEST;
        c.insets = new Insets(0, 0, 0, 10);
        Object[][] campos = {{"Data (AAAA-MM-DD)", data}, {"Inicio (HH:MM)", ini}, {"Fim (HH:MM)", fim},
            {"Capacidade minima", minimo}};
        for (int i = 0; i < campos.length; i++) {
            c.gridx = i;
            c.gridy = 0;
            filtros.add(Visual.subtitulo((String) campos[i][0]), c);
            c.gridy = 1;
            filtros.add((Component) campos[i][1], c);
        }
        c.gridx = campos.length;
        filtros.add(buscar, c);
        c.gridx++;
        filtros.add(limpar, c);

        Runnable listar = () -> {
            Map<String, Object> m = req("list_rooms");
            String d = data.getText().trim();
            if (!d.isEmpty() || !ini.getText().trim().isEmpty() || !fim.getText().trim().isEmpty()) {
                conferir("date", d, Protocolo.DATA, false);
                conferir("start_time", ini.getText().trim(), Protocolo.HORA, false);
                conferir("end_time", fim.getText().trim(), Protocolo.HORA, false);
                m.put("date", d);
                m.put("start_time", ini.getText().trim());
                m.put("end_time", fim.getText().trim());
            }
            if (!minimo.getText().trim().isEmpty()) {
                conferir("min_capacity", minimo.getText().trim(), Protocolo.CAPACIDADE, false);
                m.put("min_capacity", minimo.getText().trim());
            }
            carregar(m, r -> {
                modeloSalas.setRowCount(0);
                if (!sucesso(r)) return;
                for (Map<?, ?> s : itens(r, "rooms")) {
                    String disp = "-";
                    if (s.containsKey("available")) {
                        disp = "true".equals(s.get("available")) ? "Livre"
                                : "inactive".equals(s.get("room_status")) ? "Indisponivel" : "Ocupada";
                    }
                    modeloSalas.addRow(new Object[] {s.get("room_id"), s.get("name"), s.get("capacity"), s.get("location"),
                        juntar(s.get("resources")), statusSala(s.get("room_status")), disp});
                }
            });
        };
        buscar.addActionListener(e -> listar.run());
        limpar.addActionListener(e -> {
            data.setText("");
            ini.setText("");
            fim.setText("");
            minimo.setText("");
            listar.run();
        });
        aoAbrir.put("salas", listar);

        tabelaSalas = Visual.tabela(modeloSalas);
        larguras(tabelaSalas, 50, 200, 110, 180, 160, 120, 100);
        JButton reservar = Visual.botao("Reservar a sala selecionada", "secundario");
        reservar.addActionListener(e -> {
            int l = tabelaSalas.getSelectedRow();
            if (l < 0) {
                eventoRede("AVISO", "Selecione uma sala na tabela.");
                return;
            }
            salaParaReservar = String.valueOf(modeloSalas.getValueAt(l, 0));
            abrir("reservar");
        });

        JPanel corpo = new JPanel(new BorderLayout(0, 12));
        corpo.setOpaque(false);
        JPanel barraFiltros = new JPanel(new BorderLayout());
        barraFiltros.setOpaque(false);
        barraFiltros.add(filtros, BorderLayout.WEST);
        corpo.add(barraFiltros, BorderLayout.NORTH);
        corpo.add(Visual.rolagem(tabelaSalas), BorderLayout.CENTER);
        corpo.add(linhaBotoes(reservar), BorderLayout.SOUTH);
        return pagina("Salas", "Preencha data, inicio e fim para ver se cada sala esta livre nesse horario.", null, corpo);
    }

    // =====================================================================
    // RESERVAR / MINHAS RESERVAS
    // =====================================================================

    private String salaParaReservar;
    private final JComboBox<String> comboSalas = new JComboBox<>();

    private JComponent telaReservar() {
        JTextField data = Visual.campo(12);
        JTextField ini = Visual.campo(8);
        JTextField fim = Visual.campo(8);
        JTextField assunto = Visual.campo(24);
        JTextField part = Visual.campo(6);
        comboSalas.setFont(Visual.FONTE);
        JButton reservar = Visual.botao("Reservar", "primario");

        reservar.addActionListener(ev -> {
            Object escolhida = comboSalas.getSelectedItem();
            String sala = escolhida == null ? "" : String.valueOf(escolhida).split(" ")[0];
            conferir("room_id", sala, Protocolo.ID, false);
            conferir("date", data.getText().trim(), Protocolo.DATA, false);
            conferir("start_time", ini.getText().trim(), Protocolo.HORA, false);
            conferir("end_time", fim.getText().trim(), Protocolo.HORA, false);
            conferir("topic", assunto.getText(), Protocolo.ASSUNTO, false);
            conferir("participants", part.getText().trim(), Protocolo.CAPACIDADE, false);
            Map<String, Object> m = req("create_reservation");
            m.put("room_id", sala);
            m.put("date", data.getText().trim());
            m.put("start_time", ini.getText().trim());
            m.put("end_time", fim.getText().trim());
            m.put("topic", assunto.getText());
            m.put("participants", part.getText().trim());
            enviar(m, r -> {
                if (sucesso(r)) {
                    faixaTexto.setText(faixaTexto.getText() + "   -   reserva n " + Rede.texto(r, "reservation_id"));
                }
            });
        });

        aoAbrir.put("reservar", () -> carregar(req("list_rooms"), r -> {
            comboSalas.removeAllItems();
            if (!sucesso(r)) return;
            for (Map<?, ?> s : itens(r, "rooms")) {
                String item = s.get("room_id") + " - " + s.get("name") + " (" + s.get("capacity") + " lugares"
                        + ("inactive".equals(s.get("room_status")) ? ", indisponivel" : "") + ")";
                comboSalas.addItem(item);
                if (String.valueOf(s.get("room_id")).equals(salaParaReservar)) comboSalas.setSelectedItem(item);
            }
            salaParaReservar = null;
        }));

        JPanel form = Visual.formulario(new Object[][] {
            {"Sala", comboSalas, null},
            {"Data", data, "AAAA-MM-DD, por exemplo 2026-12-10"},
            {"Inicio", ini, "HH:MM (24 h)"},
            {"Fim", fim, "HH:MM, depois do inicio"},
            {"Assunto", assunto, "Sem acentos"},
            {"Participantes", part, "Ate a capacidade da sala"},
        });
        JPanel cartao = Visual.cartao();
        JPanel col = coluna();
        col.add(esquerda(limitar(form, 420)));
        col.add(Box.createVerticalStrut(16));
        col.add(esquerda(botaoFormulario(reservar)));
        cartao.add(col, BorderLayout.CENTER);
        JPanel corpo = coluna();
        corpo.add(esquerda(cartao));
        return pagina("Reservar sala", "A lista de salas vem do servidor (list_rooms).", null, rolavel(corpo));
    }

    private final DefaultTableModel modeloMinhas = modelo("N", "Sala", "Data", "Horario", "Participantes", "Assunto");

    private JComponent telaMinhas() {
        JTable t = Visual.tabela(modeloMinhas);
        larguras(t, 50, 220, 110, 120, 110, 260);
        JButton atualizar = Visual.botao("Atualizar", "secundario");
        Runnable carregar = () -> {
            Map<String, Object> m = req("list_reservations");
            m.put("scope", "mine");
            carregar(m, r -> {
                modeloMinhas.setRowCount(0);
                if (!sucesso(r)) return;
                for (Map<?, ?> x : itens(r, "reservations")) {
                    modeloMinhas.addRow(new Object[] {x.get("reservation_id"), x.get("room_id") + " - " + x.get("name"),
                        x.get("date"), x.get("start_time") + " - " + x.get("end_time"), x.get("participants"), x.get("topic")});
                }
            });
        };
        atualizar.addActionListener(e -> carregar.run());
        aoAbrir.put("minhas", carregar);
        return pagina("Minhas reservas", "Reservas feitas com esta conta (list_reservations, scope mine).",
                linhaBotoes(atualizar), Visual.rolagem(t));
    }

    // =====================================================================
    // ADMINISTRADOR: USUARIOS
    // =====================================================================

    private final DefaultTableModel modeloUsuarios = modelo("Usuario", "Email", "Perfil");

    private JComponent telaUsuarios() {
        JTable t = Visual.tabela(modeloUsuarios);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton atualizar = Visual.botao("Atualizar", "secundario");
        JButton ver = Visual.botao("Ver detalhes", "secundario");
        JButton alterar = Visual.botao("Alterar", "secundario");
        JButton remover = Visual.botao("Remover", "perigo");

        Runnable carregar = () -> carregar(req("admin_list_users"), r -> {
            modeloUsuarios.setRowCount(0);
            if (!sucesso(r)) return;
            for (Map<?, ?> u : itens(r, "users")) {
                modeloUsuarios.addRow(new Object[] {u.get("user"), u.get("email"),
                    "admin".equals(u.get("role")) ? "Administrador" : "Usuario comum"});
            }
        });
        atualizar.addActionListener(e -> carregar.run());
        aoAbrir.put("usuarios", carregar);

        ver.addActionListener(e -> {
            String alvo = selecionado(t, 0);
            if (alvo == null) return;
            Map<String, Object> m = req("admin_read_user");
            m.put("target_user", alvo);
            enviar(m, r -> {
                if (!sucesso(r)) return;
                JPanel p = new JPanel(new GridLayout(4, 2, 16, 8));
                p.add(Visual.subtitulo("Usuario"));
                p.add(Visual.rotulo(Rede.texto(r, "user")));
                p.add(Visual.subtitulo("Email"));
                p.add(Visual.rotulo(Rede.texto(r, "email")));
                p.add(Visual.subtitulo("Perfil"));
                p.add(Visual.rotulo("admin".equals(Rede.texto(r, "role")) ? "Administrador" : "Usuario comum"));
                p.add(Visual.subtitulo("Criado em"));
                p.add(Visual.rotulo(Rede.texto(r, "created_at")));
                JOptionPane.showMessageDialog(janela, p, "Usuario " + alvo, JOptionPane.PLAIN_MESSAGE);
            });
        });

        alterar.addActionListener(e -> {
            String alvo = selecionado(t, 0);
            if (alvo == null) return;
            JTextField novoUser = Visual.campo(20);
            JPasswordField novaSenha = new JPasswordField(20);
            Visual.estilizarCampo(novaSenha);
            JComboBox<String> perfil = new JComboBox<>(new String[] {"Manter", "Usuario comum", "Administrador"});
            JPanel p = Visual.formulario(new Object[][] {
                {"Novo usuario", novoUser, "Em branco = manter"},
                {"Nova senha", novaSenha, "Em branco = manter"},
                {"Perfil", perfil, null},
            });
            if (!dialogo("Alterar " + alvo, p, "Salvar")) return;
            String u = novoUser.getText().trim();
            String s = new String(novaSenha.getPassword());
            String rl = perfil.getSelectedIndex() == 1 ? "user" : perfil.getSelectedIndex() == 2 ? "admin" : "";
            conferir("user", u, Protocolo.USER, true);
            conferir("password", s, Protocolo.PASSWORD, true);
            if (u.isEmpty() && s.isEmpty() && rl.isEmpty()) {
                eventoRede("AVISO", "Os tres campos estao vazios: o servidor deve responder 400 \"Nenhum dado para atualizar\". Enviando mesmo assim.");
            }
            Map<String, Object> m = req("admin_update_user");
            m.put("target_user", alvo);
            m.put("user", u);
            m.put("password", s);
            m.put("role", rl);
            enviar(m, r -> {
                if (sucesso(r) && "admin".equals(role)) carregar.run();
            });
        });

        remover.addActionListener(e -> {
            String alvo = selecionado(t, 0);
            if (alvo == null) return;
            if (!confirmar("Remover o usuario '" + alvo + "'? O cadastro, a sessao e todas as reservas dele serao apagados.")) return;
            Map<String, Object> m = req("admin_delete_user");
            m.put("target_user", alvo);
            enviar(m, r -> {
                if (sucesso(r) && token != null && "admin".equals(role)) carregar.run();
            });
        });
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) ver.doClick();
            }
        });

        return pagina("Usuarios", "Todos os cadastros (admin_list_users). Clique duas vezes para ver detalhes.",
                linhaBotoes(atualizar, ver, alterar, remover), Visual.rolagem(t));
    }

    // =====================================================================
    // ADMINISTRADOR: SALAS (cadastrar, ver com quem reservou, alterar, remover)
    // =====================================================================

    private final DefaultTableModel modeloAdmSalas = modelo("N", "Nome", "Capacidade", "Local", "Recursos", "Status", "Reservas");
    private final DefaultTableModel modeloAgenda = modelo("N", "Quem reservou", "Data", "Horario", "Participantes", "Assunto");
    private final List<Map<?, ?>> reservasCarregadas = new ArrayList<>();
    private JLabel tituloAgenda;

    private JComponent telaAdmSalas() {
        JTable salas = Visual.tabela(modeloAdmSalas);
        salas.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        larguras(salas, 50, 200, 110, 180, 170, 120, 80);
        JTable agenda = Visual.tabela(modeloAgenda);
        larguras(agenda, 50, 160, 110, 120, 110, 260);
        tituloAgenda = Visual.rotulo("Selecione uma sala para ver quem reservou e quando");

        JButton atualizar = Visual.botao("Atualizar", "secundario");
        JButton nova = Visual.botao("Nova sala", "primario");
        JButton alterar = Visual.botao("Alterar", "secundario");
        JButton remover = Visual.botao("Remover", "perigo");

        // list_rooms + list_reservations (scope all): salas com status e, para cada uma, as reservas
        Runnable carregar = () -> {
          String antes = salas.getSelectedRow() < 0 ? null : String.valueOf(modeloAdmSalas.getValueAt(salas.getSelectedRow(), 0));
          carregar(req("list_rooms"), salasResp -> {
            if (!sucesso(salasResp)) {
                modeloAdmSalas.setRowCount(0);
                return;
            }
            Map<String, Object> m = req("list_reservations");
            m.put("scope", "all");
            carregar(m, resResp -> {
                reservasCarregadas.clear();
                if (sucesso(resResp)) reservasCarregadas.addAll(itens(resResp, "reservations"));
                modeloAdmSalas.setRowCount(0);
                for (Map<?, ?> s : itens(salasResp, "rooms")) {
                    int qtd = 0;
                    for (Map<?, ?> x : reservasCarregadas) {
                        if (String.valueOf(x.get("room_id")).equals(String.valueOf(s.get("room_id")))) qtd++;
                    }
                    modeloAdmSalas.addRow(new Object[] {s.get("room_id"), s.get("name"), s.get("capacity"), s.get("location"),
                        juntar(s.get("resources")), statusSala(s.get("room_status")), qtd});
                }
                // mantem selecionada a sala que estava antes de recarregar
                for (int i = 0; i < modeloAdmSalas.getRowCount(); i++) {
                    if (String.valueOf(modeloAdmSalas.getValueAt(i, 0)).equals(antes)) salas.setRowSelectionInterval(i, i);
                }
                mostrarAgenda(salas);
            });
          });
        };
        atualizar.addActionListener(e -> carregar.run());
        aoAbrir.put("admsalas", carregar);
        salas.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) mostrarAgenda(salas);
        });

        nova.addActionListener(e -> {
            JTextField nome = Visual.campo(22);
            JTextField cap = Visual.campo(8);
            JTextField local = Visual.campo(22);
            JTextField rec = Visual.campo(22);
            JPanel p = Visual.formulario(new Object[][] {
                {"Nome", nome, "Letras, numeros, espaco e hifen (1 a 30), sem acentos"},
                {"Capacidade", cap, "Numero de lugares (1 a 9999)"},
                {"Local", local, "Ex.: Bloco B - 1 andar"},
                {"Recursos", rec, "Separados por virgula, ex.: projetor, tv (em branco = nenhum)"},
            });
            if (!dialogo("Nova sala", p, "Cadastrar")) return;
            conferir("name", nome.getText(), Protocolo.NOME_SALA, false);
            conferir("capacity", cap.getText().trim(), Protocolo.CAPACIDADE, false);
            conferir("location", local.getText(), Protocolo.LOCAL, false);
            Map<String, Object> m = req("create_room");
            m.put("name", nome.getText());
            m.put("capacity", cap.getText().trim());
            m.put("location", local.getText());
            m.put("resources", separar(rec.getText()));
            enviar(m, r -> {
                if (sucesso(r)) {
                    faixaTexto.setText(faixaTexto.getText() + "   -   sala n " + Rede.texto(r, "room_id"));
                    carregar.run();
                }
            });
        });

        alterar.addActionListener(e -> {
            String id = selecionado(salas, 0);
            if (id == null) return;
            Map<String, Object> ler = req("read_room");
            ler.put("room_id", id);
            enviar(ler, atual -> {
                if (!sucesso(atual)) return;
                editarSala(id, atual, carregar);
            });
        });

        remover.addActionListener(e -> {
            String id = selecionado(salas, 0);
            if (id == null) return;
            if (!confirmar("Remover a sala " + id + "? Ela so pode ser removida se nao tiver reservas futuras.")) return;
            Map<String, Object> m = req("delete_room");
            m.put("room_id", id);
            enviar(m, r -> {
                if (r != null && "409".equals(Rede.texto(r, "status"))) {
                    faixaTexto.setText(faixaTexto.getText() + "   -   para tirar de uso, altere o status para Indisponivel");
                } else if (sucesso(r)) {
                    carregar.run();
                }
            });
        });

        JPanel parteSalas = new JPanel(new BorderLayout(0, 8));
        parteSalas.setOpaque(false);
        parteSalas.add(Visual.rolagem(salas), BorderLayout.CENTER);
        JPanel parteAgenda = new JPanel(new BorderLayout(0, 8));
        parteAgenda.setOpaque(false);
        parteAgenda.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        parteAgenda.add(tituloAgenda, BorderLayout.NORTH);
        parteAgenda.add(Visual.rolagem(agenda), BorderLayout.CENTER);
        JSplitPane divisao = new JSplitPane(JSplitPane.VERTICAL_SPLIT, parteSalas, parteAgenda);
        divisao.setResizeWeight(0.55);
        divisao.setBorder(null);
        divisao.setOpaque(false);
        divisao.setDividerSize(6);

        return pagina("Gerenciar salas", "Salas com status e, para a sala selecionada, quem reservou e quando.",
                linhaBotoes(atualizar, nova, alterar, remover), divisao);
    }

    private void mostrarAgenda(JTable salas) {
        modeloAgenda.setRowCount(0);
        String id = salas.getSelectedRow() < 0 ? null : String.valueOf(modeloAdmSalas.getValueAt(salas.getSelectedRow(), 0));
        if (id == null) {
            tituloAgenda.setText("Selecione uma sala para ver quem reservou e quando");
            return;
        }
        tituloAgenda.setText("Reservas da sala " + id + " - " + modeloAdmSalas.getValueAt(salas.getSelectedRow(), 1));
        for (Map<?, ?> x : reservasCarregadas) {
            if (!String.valueOf(x.get("room_id")).equals(id)) continue;
            modeloAgenda.addRow(new Object[] {x.get("reservation_id"), x.get("user"), x.get("date"),
                x.get("start_time") + " - " + x.get("end_time"), x.get("participants"), x.get("topic")});
        }
    }

    private void editarSala(String id, Map<String, Object> atual, Runnable recarregar) {
        String nomeAtual = Rede.texto(atual, "name");
        String capAtual = Rede.texto(atual, "capacity");
        String localAtual = Rede.texto(atual, "location");
        String recAtual = juntar(atual.get("resources"));
        String stAtual = Rede.texto(atual, "room_status");

        JTextField nome = Visual.campo(22);
        nome.setText(nomeAtual);
        JTextField cap = Visual.campo(8);
        cap.setText(capAtual);
        JTextField local = Visual.campo(22);
        local.setText(localAtual);
        JTextField rec = Visual.campo(22);
        rec.setText("nenhum".equals(recAtual) ? "" : recAtual);
        JComboBox<String> status = new JComboBox<>(new String[] {"Livre (active)", "Indisponivel (inactive)"});
        status.setSelectedIndex("inactive".equals(stAtual) ? 1 : 0);
        JPanel p = Visual.formulario(new Object[][] {
            {"Nome", nome, null},
            {"Capacidade", cap, null},
            {"Local", local, null},
            {"Recursos", rec, "Separados por virgula (em branco = nenhum)"},
            {"Status", status, "Indisponivel bloqueia novas reservas e mantem as existentes"},
        });
        if (!dialogo("Alterar sala " + id, p, "Salvar")) return;

        // campo igual ao atual vai como "" (= nao alterar, regra 2.11); resources vai sempre completo
        String novoSt = status.getSelectedIndex() == 1 ? "inactive" : "active";
        Map<String, Object> m = req("update_room");
        m.put("room_id", id);
        m.put("name", nome.getText().equals(nomeAtual) ? "" : nome.getText());
        m.put("capacity", cap.getText().trim().equals(capAtual) ? "" : cap.getText().trim());
        m.put("location", local.getText().equals(localAtual) ? "" : local.getText());
        m.put("resources", separar(rec.getText()));
        m.put("room_status", novoSt.equals(stAtual) ? "" : novoSt);
        conferir("name", (String) m.get("name"), Protocolo.NOME_SALA, true);
        conferir("capacity", (String) m.get("capacity"), Protocolo.CAPACIDADE, true);
        conferir("location", (String) m.get("location"), Protocolo.LOCAL, true);
        enviar(m, r -> {
            if (sucesso(r)) recarregar.run();
        });
    }

    // =====================================================================
    // LOGOUT E SAIR
    // =====================================================================

    private void logout() {
        if (token == null) return;
        enviar(req("logout"), r -> {
            // regra 3.10: sem logout_response o token nao e descartado
            if (r == null && token != null) eventoRede("INFO", "Logout sem resposta: o token foi mantido. Tente de novo.");
        });
    }

    private void sair() {
        if (token != null && rede.conectado()) {
            int r = JOptionPane.showConfirmDialog(janela, "Voce esta logado. Fazer logout antes de sair?", "Sair",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
            if (r == JOptionPane.YES_OPTION) {
                Map<String, Object> m = req("logout");
                rede.enviar(m); // ao fechar, espera a resposta aqui mesmo
            }
        }
        rede.fechar();
        System.exit(0);
    }

    // =====================================================================
    // AUXILIARES DE TELA
    // =====================================================================

    /** As telas que precisam de login sao criadas uma vez so, na primeira montagem. */
    {
        registrarTela("meu", telaMeuCadastro(), this::carregarMeuCadastro);
        registrarTela("salas", telaSalas(), null);
        registrarTela("reservar", telaReservar(), null);
        registrarTela("minhas", telaMinhas(), null);
        registrarTela("usuarios", telaUsuarios(), null);
        registrarTela("admsalas", telaAdmSalas(), null);
    }

    /** Pagina padrao: titulo, subtitulo, barra de botoes (opcional) e conteudo que ocupa o resto. */
    private static JComponent pagina(String titulo, String sub, JComponent barra, JComponent conteudo) {
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setOpaque(false);
        JPanel topo = new JPanel(new BorderLayout());
        topo.setOpaque(false);
        JPanel textos = coluna();
        textos.add(esquerda(Visual.titulo(titulo)));
        textos.add(Box.createVerticalStrut(2));
        textos.add(esquerda(Visual.subtitulo(sub)));
        topo.add(textos, BorderLayout.WEST);
        if (barra != null) topo.add(barra, BorderLayout.EAST);
        p.add(topo, BorderLayout.NORTH);
        p.add(conteudo, BorderLayout.CENTER);
        return p;
    }

    /** Pagina de formulario centralizado (login e cadastro). */
    private static JComponent paginaFormulario(String titulo, String sub, JPanel form, JButton principal, JButton secundario) {
        JPanel cartao = Visual.cartao();
        cartao.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Visual.BORDA), Visual.margem(26, 30)));
        JPanel col = coluna();
        col.add(esquerda(Visual.titulo(titulo)));
        col.add(Box.createVerticalStrut(4));
        for (String linha : sub.split("\n")) col.add(esquerda(Visual.subtitulo(linha)));
        col.add(Box.createVerticalStrut(20));
        col.add(esquerda(form));
        col.add(Box.createVerticalStrut(20));
        JPanel botoes = new JPanel(new GridLayout(1, 2, 10, 0));
        botoes.setOpaque(false);
        botoes.add(secundario);
        botoes.add(principal);
        col.add(esquerda(botoes));
        cartao.add(col, BorderLayout.CENTER);
        JPanel fundo = new JPanel(new GridBagLayout());
        fundo.setOpaque(false);
        fundo.add(larguraFixa(cartao, 440));
        return fundo;
    }

    private static JPanel coluna() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    private static JComponent esquerda(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /** Largura fixa; a altura acompanha o conteudo (calculada na hora de desenhar). */
    private static JComponent larguraFixa(JComponent c, int largura) {
        JPanel p = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override public Dimension getPreferredSize() {
                return new Dimension(largura, super.getPreferredSize().height);
            }
        };
        p.setOpaque(false);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    /** Largura maxima para formularios nao esticarem em telas grandes. */
    private static JComponent limitar(JComponent c, int max) {
        JPanel p = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override public Dimension getMaximumSize() {
                return new Dimension(max, super.getPreferredSize().height);
            }

            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(Math.min(max, Math.max(d.width, max)), d.height);
            }
        };
        p.setOpaque(false);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    /** Botoes alinhados a direita (barras de acoes de tabelas). */
    private static JPanel linhaBotoes(JButton... botoes) {
        return botoes(FlowLayout.RIGHT, 8, botoes);
    }

    /** Botao de formulario, alinhado a esquerda com o campo. */
    private static JPanel botaoFormulario(JButton botao) {
        return botoes(FlowLayout.LEFT, 0, botao);
    }

    private static JPanel botoes(int alinhamento, int espaco, JButton... botoes) {
        JPanel p = new JPanel(new FlowLayout(alinhamento, espaco, 0));
        p.setOpaque(false);
        for (JButton b : botoes) {
            p.add(b);
            if (espaco == 0 && botoes.length > 1) p.add(Box.createHorizontalStrut(8));
        }
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private static JScrollPane rolavel(JComponent c) {
        JPanel envoltorio = new JPanel(new BorderLayout());
        envoltorio.setOpaque(false);
        envoltorio.add(c, BorderLayout.NORTH);
        JScrollPane s = new JScrollPane(envoltorio);
        s.setBorder(null);
        s.setOpaque(false);
        s.getViewport().setOpaque(false);
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    private static JLabel valor() {
        JLabel l = new JLabel("-");
        l.setFont(Visual.FONTE_NEGRITO.deriveFont(15f));
        l.setForeground(Visual.TEXTO);
        return l;
    }

    private static DefaultTableModel modelo(String... colunas) {
        return new DefaultTableModel(colunas, 0) {
            private static final long serialVersionUID = 1L;

            @Override public boolean isCellEditable(int l, int c) { return false; }
        };
    }

    private static void larguras(JTable t, int... w) {
        for (int i = 0; i < w.length && i < t.getColumnCount(); i++) {
            t.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
    }

    private String selecionado(JTable t, int coluna) {
        int l = t.getSelectedRow();
        if (l < 0) {
            JOptionPane.showMessageDialog(janela, "Selecione uma linha da tabela primeiro.", "Nada selecionado",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return String.valueOf(t.getModel().getValueAt(t.convertRowIndexToModel(l), coluna));
    }

    private boolean dialogo(String titulo, JPanel conteudo, String textoOk) {
        conteudo.setBorder(Visual.margem(6, 4));
        conteudo.setPreferredSize(new Dimension(440, conteudo.getPreferredSize().height));
        Object[] opcoes = {textoOk, "Cancelar"};
        int r = JOptionPane.showOptionDialog(janela, conteudo, titulo, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, opcoes, opcoes[0]);
        return r == 0;
    }

    private boolean confirmar(String texto) {
        Object[] opcoes = {"Sim, remover", "Cancelar"};
        int r = JOptionPane.showOptionDialog(janela, texto, "Confirmar", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE, null, opcoes, opcoes[1]);
        return r == 0;
    }

    /** So avisa no painel de mensagens: o valor e enviado mesmo assim, para o servidor mostrar a validacao dele. */
    private void conferir(String campo, String valor, Pattern regex, boolean vazioPermitido) {
        if (vazioPermitido && valor.isEmpty()) return;
        if (!regex.matcher(valor).matches()) {
            eventoRede("AVISO", "'" + campo + "' nao segue o formato " + regex.pattern()
                    + " -> o servidor deve recusar (400). Enviando mesmo assim.");
        }
    }

    private static boolean sucesso(Map<String, Object> r) {
        if (r == null) return false;
        String st = Rede.texto(r, "status");
        return st != null && st.startsWith("2");
    }

    private static List<Map<?, ?>> itens(Map<String, Object> r, String chave) {
        List<Map<?, ?>> l = new ArrayList<>();
        Object v = r.get(chave);
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Map) l.add((Map<?, ?>) o);
            }
        }
        return l;
    }

    private static String juntar(Object lista) {
        if (!(lista instanceof List) || ((List<?>) lista).isEmpty()) return "nenhum";
        List<String> partes = new ArrayList<>();
        for (Object o : (List<?>) lista) partes.add(String.valueOf(o));
        return String.join(", ", partes);
    }

    private static List<Object> separar(String texto) {
        List<Object> l = new ArrayList<>();
        if (texto.trim().equals("-")) return l;
        for (String p : texto.split(",")) {
            if (!p.trim().isEmpty()) l.add(p.trim());
        }
        return l;
    }

    private static String statusSala(Object st) {
        if ("active".equals(st)) return "Livre";
        if ("inactive".equals(st)) return "Indisponivel";
        return String.valueOf(st);
    }
}
