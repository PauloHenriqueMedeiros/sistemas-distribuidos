package cliente;

import comum.Json;
import comum.Log;
import comum.Protocolo;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CLIENTE - Sistema de Reserva de Salas (EP-1)
 *
 * Como rodar: java -jar Cliente.jar   (ou a classe cliente.Cliente)
 * Pede IP e porta do servidor e mostra um menu com as operacoes do EP-1.
 * Toda mensagem ENVIADA e RECEBIDA aparece no terminal, junto com o resultado.
 */
public final class Cliente {

    private static final int TIMEOUT_CONEXAO_MS = 5_000;
    private static final int TIMEOUT_RESPOSTA_MS = 15_000;
    /** O servidor fecha a conexao apos 300 s parado; reconectamos um pouco antes disso. */
    private static final long RECONECTAR_SE_PARADO_MS = 280_000;

    private final BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

    private String ip;
    private int porta;
    private Socket socket;
    private InputStream entrada;
    private OutputStream saida;
    private long ultimaAtividade;

    private String token; // guardado so em memoria (regra 3.2)
    private String role;

    public static void main(String[] args) {
        new Cliente().executar();
    }

    private void executar() {
        System.out.println("==============================================================");
        System.out.println("   CLIENTE - Sistema de Reserva de Salas (protocolo v" + Protocolo.VERSAO + ")");
        System.out.println("==============================================================");
        escolherServidor();

        while (true) {
            mostrarMenu();
            String opcao = ler("Escolha uma opcao: ").trim();
            switch (opcao) {
                case "1":
                    if (token == null) {
                        cadastrar();
                    } else {
                        System.out.println("Voce ja esta logado. Para cadastrar outro usuario, faca logout primeiro (opcao 6).");
                    }
                    break;
                case "2":
                    if (token == null) {
                        login();
                    } else {
                        System.out.println("Voce ja esta logado. Para entrar com outra conta, faca logout primeiro (opcao 6).");
                    }
                    break;
                case "3": verCadastro();     break;
                case "4": atualizar();       break;
                case "5": apagarCadastro();  break;
                case "6": logout();          break;
                case "10": adminListar();    break;
                case "11": adminVer();       break;
                case "12": adminAlterar();   break;
                case "13": adminRemover();   break;
                case "0": sair();            return;
                default: System.out.println("Opcao invalida.");
            }
        }
    }

    private void mostrarMenu() {
        System.out.println();
        System.out.println("============================ MENU ============================");
        System.out.println(" Servidor: " + ip + ":" + porta + (conectado() ? "  (conectado)" : "  (desconectado)"));
        System.out.println(" Sessao  : " + (token == null ? "nao logado" : "logado  (role: " + role + ")"));
        System.out.println("--------------------------------------------------------------");
        if (token == null) {
            System.out.println(" 1 - Cadastrar usuario            (register)");
            System.out.println(" 2 - Login                        (login)");
        }
        System.out.println(" 3 - Ver meu cadastro             (read_user)");
        System.out.println(" 4 - Atualizar meu cadastro       (update_user)");
        System.out.println(" 5 - Apagar meu cadastro          (delete_user)");
        System.out.println(" 6 - Logout                       (logout)");
        System.out.println(" 0 - Sair");
        if ("admin".equals(role)) {
            System.out.println("------------------------ ADMINISTRADOR -----------------------");
            System.out.println(" 10 - Listar todos os usuarios    (admin_list_users)");
            System.out.println(" 11 - Ver um usuario              (admin_read_user)");
            System.out.println(" 12 - Alterar um usuario          (admin_update_user)");
            System.out.println(" 13 - Remover um usuario          (admin_delete_user)");
        }
        System.out.println("==============================================================");
    }

    // =====================================================================
    // OPERACOES DO MENU
    // =====================================================================

    private void cadastrar() {
        System.out.println("\n--- CADASTRO (register) ---");
        System.out.println("Formato: email ex. nome.sobrenome@email.com ou nome@alunos.utfpr.edu.br (minusculas)");
        System.out.println("         usuario: so letras minusculas (1 a 30) | senha: letras e numeros, sem espacos (1 a 20)");
        String email = ler("Email  : ");
        String user = ler("Usuario: ");
        String senha = ler("Senha  : ");
        conferirLocal("email", email, Protocolo.EMAIL, false);
        conferirLocal("user", user, Protocolo.USER, false);
        conferirLocal("password", senha, Protocolo.PASSWORD, false);

        Map<String, Object> req = novaRequisicao("register");
        req.put("email", email);
        req.put("user", user);
        req.put("password", senha);
        executar(req);
    }

    private void login() {
        System.out.println("\n--- LOGIN (login) ---");
        String email = ler("Email: ");
        String senha = ler("Senha: ");
        conferirLocal("email", email, Protocolo.EMAIL, false);
        conferirLocal("password", senha, Protocolo.PASSWORD, false);

        Map<String, Object> req = novaRequisicao("login");
        req.put("email", email);
        req.put("password", senha);
        executar(req);
    }

    private void verCadastro() {
        System.out.println("\n--- MEU CADASTRO (read_user) ---");
        if (!exigirLogin()) return;
        Map<String, Object> req = novaRequisicao("read_user");
        req.put("token", token);
        executar(req);
    }

    private void atualizar() {
        System.out.println("\n--- ATUALIZAR CADASTRO (update_user) ---");
        if (!exigirLogin()) return;
        System.out.println("Deixe em branco (so Enter) o que NAO quiser alterar. O email nao pode ser alterado.");
        String user = ler("Novo usuario: ");
        String senha = ler("Nova senha  : ");
        conferirLocal("user", user, Protocolo.USER, true);
        conferirLocal("password", senha, Protocolo.PASSWORD, true);
        if (user.isEmpty() && senha.isEmpty()) {
            Log.print("[AVISO]", "Os dois campos estao vazios: o servidor deve responder 400 "
                    + "\"Nenhum dado para atualizar\" (regra 2.13). Enviando mesmo assim.");
        }

        Map<String, Object> req = novaRequisicao("update_user");
        req.put("token", token);
        req.put("user", user);
        req.put("password", senha);
        executar(req);
    }

    private void apagarCadastro() {
        System.out.println("\n--- APAGAR MEU CADASTRO (delete_user) ---");
        if (!exigirLogin()) return;
        String senha = ler("Confirme sua senha: ");
        String certeza = ler("Tem certeza que deseja apagar sua conta? (s/n): ").trim().toLowerCase();
        if (!certeza.equals("s")) {
            System.out.println("Cancelado. Nada foi enviado.");
            return;
        }
        conferirLocal("password", senha, Protocolo.PASSWORD, false);

        Map<String, Object> req = novaRequisicao("delete_user");
        req.put("token", token);
        req.put("password", senha);
        executar(req);
    }

    private void logout() {
        System.out.println("\n--- LOGOUT (logout) ---");
        if (!exigirLogin()) return;
        Map<String, Object> req = novaRequisicao("logout");
        req.put("token", token);
        executar(req);
    }

    // =====================================================================
    // OPERACOES DO ADMINISTRADOR (so aparecem no menu quando logado como admin)
    // =====================================================================

    private void adminListar() {
        System.out.println("\n--- LISTAR USUARIOS (admin_list_users) ---");
        if (!exigirAdmin()) return;
        Map<String, Object> req = novaRequisicao("admin_list_users");
        req.put("token", token);
        executar(req);
    }

    private void adminVer() {
        System.out.println("\n--- VER UM USUARIO (admin_read_user) ---");
        if (!exigirAdmin()) return;
        String alvo = ler("Usuario a consultar: ");
        conferirLocal("target_user", alvo, Protocolo.USER, false);
        Map<String, Object> req = novaRequisicao("admin_read_user");
        req.put("token", token);
        req.put("target_user", alvo);
        executar(req);
    }

    private void adminAlterar() {
        System.out.println("\n--- ALTERAR UM USUARIO (admin_update_user) ---");
        if (!exigirAdmin()) return;
        String alvo = ler("Usuario a alterar: ");
        System.out.println("Deixe em branco (so Enter) o que NAO quiser alterar. O email nao pode ser alterado.");
        String user = ler("Novo usuario      : ");
        String senha = ler("Nova senha        : ");
        String novoRole = ler("Novo role (user/admin): ").trim();
        conferirLocal("target_user", alvo, Protocolo.USER, false);
        conferirLocal("user", user, Protocolo.USER, true);
        conferirLocal("password", senha, Protocolo.PASSWORD, true);
        conferirLocal("role", novoRole, Protocolo.ROLE, true);
        if (user.isEmpty() && senha.isEmpty() && novoRole.isEmpty()) {
            Log.print("[AVISO]", "Os tres campos estao vazios: o servidor deve responder 400 "
                    + "\"Nenhum dado para atualizar\" (regra 2.13). Enviando mesmo assim.");
        }
        Map<String, Object> req = novaRequisicao("admin_update_user");
        req.put("token", token);
        req.put("target_user", alvo);
        req.put("user", user);
        req.put("password", senha);
        req.put("role", novoRole);
        executar(req);
    }

    private void adminRemover() {
        System.out.println("\n--- REMOVER UM USUARIO (admin_delete_user) ---");
        if (!exigirAdmin()) return;
        String alvo = ler("Usuario a remover: ");
        String certeza = ler("Tem certeza que deseja remover '" + alvo + "'? (s/n): ").trim().toLowerCase();
        if (!certeza.equals("s")) {
            System.out.println("Cancelado. Nada foi enviado.");
            return;
        }
        conferirLocal("target_user", alvo, Protocolo.USER, false);
        Map<String, Object> req = novaRequisicao("admin_delete_user");
        req.put("token", token);
        req.put("target_user", alvo);
        executar(req);
    }

    private void sair() {
        if (token != null) {
            String r = ler("Voce esta logado. Fazer logout antes de sair? (s/n): ").trim().toLowerCase();
            if (r.equals("s")) logout();
        }
        fecharConexao();
        System.out.println("Cliente encerrado.");
    }

    // =====================================================================
    // ENVIO / RECEBIMENTO
    // =====================================================================

    /** Serializa, envia, recebe e aplica os efeitos da resposta (guardar/descartar token). */
    private Map<String, Object> executar(Map<String, Object> req) {
        String op = (String) req.get("op");
        Map<String, Object> resposta = enviarEReceber(Json.escrever(req), op);
        if (resposta != null) {
            aplicarEfeitos(op, resposta, texto(req, "token"));
        } else if ("logout".equals(op) && token != null) {
            // Regra 3.10: sem logout_response o token NAO e descartado (a sessao pode continuar ativa no servidor)
            Log.print("[INFO]", "Logout sem resposta: o token foi mantido. Tente o logout de novo (opcao 6).");
        }
        return resposta;
    }

    /**
     * Envia a linha e espera a resposta.
     * Regra 1.11 (v2.2): se a conexao tiver caido ou sido fechada pelo servidor (inatividade de 300 s),
     * reconecta e reenvia a MESMA requisicao uma vez.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> enviarEReceber(String linha, String opEnviada) {
        if (conectado() && System.currentTimeMillis() - ultimaAtividade > RECONECTAR_SE_PARADO_MS) {
            Log.print("[INFO]", "Conexao parada ha mais de 280 s (o servidor fecha em 300 s). Reconectando antes de enviar...");
            fecharConexao();
        }

        String textoResposta = null;
        for (int tentativa = 1; tentativa <= 2 && textoResposta == null; tentativa++) {
            if (!conectado()) {
                Log.print("[INFO]", "Sem conexao. Tentando conectar em " + ip + ":" + porta + "...");
                if (!conectar()) {
                    Log.print("[ERRO]", "Nao foi possivel enviar: sem conexao com o servidor. "
                            + "Confira se ele esta ligado ou reinicie o cliente com outro IP/porta.");
                    return null;
                }
            }
            try {
                Protocolo.enviar(saida, linha);
                Log.print("[ENVIADO]", "-> " + linha + (tentativa > 1 ? "   (reenvio)" : ""));
                ultimaAtividade = System.currentTimeMillis();

                Protocolo.Linha resp = Protocolo.lerLinha(entrada);
                ultimaAtividade = System.currentTimeMillis();
                if (resp == null) {
                    fecharConexao();
                    if (tentativa == 1) {
                        Log.print("[INFO]", "O servidor fechou a conexao sem responder (talvez por inatividade). "
                                + "Reconectando e reenviando a mesma requisicao (regra 1.11)...");
                        continue;
                    }
                    Log.print("[ERRO]", "O servidor encerrou a conexao sem responder, de novo.");
                    return null;
                }
                if (resp.excedeuTamanho) {
                    Log.print("[ERRO]", "A resposta do servidor passou de " + Protocolo.TAMANHO_MAXIMO
                            + " bytes (fora do protocolo).");
                    return null;
                }
                textoResposta = resp.texto;
            } catch (SocketTimeoutException e) {
                // nao reenvia: o servidor pode ter recebido e ainda estar processando
                Log.print("[ERRO]", "O servidor nao respondeu em " + (TIMEOUT_RESPOSTA_MS / 1000)
                        + " s. Conexao encerrada (o servidor deveria sempre responder).");
                fecharConexao();
                return null;
            } catch (IOException e) {
                fecharConexao();
                if (tentativa == 1) {
                    Log.print("[INFO]", "A conexao caiu (" + e.getMessage()
                            + "). Reconectando e reenviando a mesma requisicao (regra 1.11)...");
                    continue;
                }
                Log.print("[ERRO]", "Falha de comunicacao com o servidor: " + e.getMessage());
                return null;
            }
        }

        Log.print("[RECEBIDO]", "<- " + textoResposta);

        Object lido;
        try {
            lido = Json.ler(textoResposta);
        } catch (Json.ErroJson e) {
            Log.print("[ERRO]", "A resposta do servidor NAO e um JSON valido (" + e.getMessage() + ").");
            return null;
        }
        if (!(lido instanceof Map)) {
            Log.print("[ERRO]", "A resposta do servidor nao e um objeto JSON { ... }.");
            return null;
        }
        Map<String, Object> resposta = (Map<String, Object>) lido;

        String status = texto(resposta, "status");
        String opResposta = texto(resposta, "op");
        String tipo;
        if ("error".equals(opResposta)) {
            tipo = "ERRO DE PROTOCOLO";
        } else if (status != null && status.startsWith("2")) {
            tipo = "SUCESSO";
        } else {
            tipo = "FALHA";
        }
        System.out.println(">>> " + tipo + " [status " + String.valueOf(resposta.get("status")) + "] "
                + String.valueOf(resposta.get("message")));

        for (String aviso : validarResposta(resposta, opEnviada)) {
            Log.print("[AVISO]", "Resposta fora do protocolo: " + aviso);
        }
        return resposta;
    }

    /**
     * Guarda ou descarta o token conforme a resposta e mostra dados de leitura.
     * tokenUsado = token que foi enviado na requisicao; o token guardado so e
     * descartado se foi ELE que o servidor invalidou/recusou.
     */
    private void aplicarEfeitos(String op, Map<String, Object> r, String tokenUsado) {
        String status = texto(r, "status");
        if (status == null) return;
        boolean usouMeuToken = token != null && token.equals(tokenUsado);

        switch (op) {
            case "login":
                if (status.equals("200") && texto(r, "token") != null) {
                    token = texto(r, "token");
                    role = texto(r, "role");
                    Log.print("[INFO]", "Token guardado em memoria. Voce esta logado como '" + role + "'.");
                } else if (status.equals("409")) {
                    Log.print("[INFO]", "Ja existe sessao ativa para esse usuario. Faca logout com o token dela "
                            + "ou aguarde 30 min sem uso.");
                }
                break;
            case "logout":
            case "delete_user":
                if (status.equals("200") && usouMeuToken) {
                    token = null;
                    role = null;
                    Log.print("[INFO]", "Token descartado. Voce nao esta mais logado.");
                } else if (status.equals("403") && usouMeuToken) {
                    // v2.1 (regra 3.9): 403 nao derruba a sessao
                    Log.print("[INFO]", "Nada foi removido e a sessao continua ativa (token mantido).");
                }
                break;
            case "admin_list_users":
                if (status.equals("200")) mostrarListaUsuarios(r);
                break;
            case "read_user":
            case "admin_read_user":
                if (status.equals("200")) {
                    System.out.println("    Usuario ..: " + texto(r, "user"));
                    System.out.println("    Email ....: " + texto(r, "email"));
                    System.out.println("    Role .....: " + texto(r, "role"));
                    System.out.println("    Criado em : " + texto(r, "created_at"));
                }
                break;
            default:
                break;
        }

        // 403 "Permissao insuficiente" numa operacao de admin: o servidor diz que este token nao e de admin
        // (ex.: o proprio role foi rebaixado). A sessao continua; so as opcoes de admin somem do menu.
        if (status.equals("403") && op.startsWith("admin_") && usouMeuToken
                && "Permissao insuficiente".equals(texto(r, "message")) && "admin".equals(role)) {
            role = "user";
            Log.print("[INFO]", "O servidor nao reconhece este login como admin. As opcoes de admin foram escondidas.");
        }

        // Regra 3.9 (v2.1): 401 (fora do login) significa que o servidor nao tem sessao para esse token,
        // entao o cliente descarta o token e volta ao login.
        if (status.equals("401") && !op.equals("login") && usouMeuToken) {
            token = null;
            role = null;
            Log.print("[INFO]", "O servidor nao reconhece mais o token. Token descartado: faca login novamente (opcao 2).");
        }
    }

    /** Confere se a resposta segue o protocolo e devolve a lista de problemas encontrados. */
    private List<String> validarResposta(Map<String, Object> r, String opEnviada) {
        List<String> avisos = new ArrayList<>();
        String esperado = opEnviada == null ? "error" : opEnviada + "_response";

        String op = texto(r, "op");
        if (op == null) {
            avisos.add("falta a chave 'op' (string)");
        } else if (!op.equals(esperado) && !op.equals("error")) {
            avisos.add("'op' deveria ser '" + esperado + "', mas veio '" + op + "'");
        }
        String status = texto(r, "status");
        if (!Protocolo.casa(Protocolo.STATUS, status)) {
            avisos.add("'status' ausente ou fora do formato (string com 3 digitos, ex: \"200\")");
        }
        if (texto(r, "message") == null) {
            avisos.add("'message' ausente ou nao e string");
        }
        for (Map.Entry<String, Object> e : r.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                avisos.add("campo '" + e.getKey() + "' veio null");
            } else if (!(v instanceof String) && !(v instanceof List)) {
                avisos.add("campo '" + e.getKey() + "' deveria ser string (regra 2.3)");
            }
        }
        if (r.containsKey("password")) {
            avisos.add("a resposta contem 'password' (a senha nunca deve ser devolvida)");
        }
        if ("register".equals(opEnviada) && "200".equals(status)) {
            avisos.add("cadastro com sucesso deveria responder status 201");
        }
        if (opEnviada != null && !"register".equals(opEnviada) && "201".equals(status)) {
            avisos.add("'" + opEnviada + "' com sucesso deveria responder status 200");
        }
        if ("200".equals(status) && "login".equals(opEnviada)) {
            if (!Protocolo.casa(Protocolo.TOKEN, texto(r, "token"))) {
                avisos.add("'token' ausente ou fora do formato ^[a-f0-9]{64}$");
            }
            if (!Protocolo.casa(Protocolo.ROLE, texto(r, "role"))) {
                avisos.add("'role' ausente ou diferente de \"user\"/\"admin\"");
            }
        }
        if ("200".equals(status) && "admin_list_users".equals(opEnviada)) {
            Object lista = r.get("users");
            if (!(lista instanceof List)) {
                avisos.add("falta o array 'users'");
            } else {
                List<?> itens = (List<?>) lista;
                if (!String.valueOf(itens.size()).equals(texto(r, "count"))) {
                    avisos.add("'count' (" + texto(r, "count") + ") diferente da quantidade de itens em 'users' (" + itens.size() + ")");
                }
                for (Object item : itens) {
                    if (!(item instanceof Map)) {
                        avisos.add("item de 'users' nao e objeto");
                        break;
                    }
                    Map<?, ?> m = (Map<?, ?>) item;
                    if (m.containsKey("password")) avisos.add("um item de 'users' contem 'password'");
                    for (String campo : new String[] {"user", "email", "role"}) {
                        if (!(m.get(campo) instanceof String)) {
                            avisos.add("item de 'users' sem '" + campo + "' (string)");
                        }
                    }
                }
            }
        }
        if ("200".equals(status) && ("read_user".equals(opEnviada) || "admin_read_user".equals(opEnviada))) {
            if (texto(r, "user") == null) avisos.add("falta 'user'");
            if (texto(r, "email") == null) avisos.add("falta 'email'");
            if (!Protocolo.casa(Protocolo.ROLE, texto(r, "role"))) avisos.add("'role' ausente ou invalido");
            if (!Protocolo.casa(Protocolo.CREATED_AT, texto(r, "created_at"))) {
                avisos.add("'created_at' ausente ou fora do formato AAAA-MM-DD HH:MM:SS");
            }
        }
        return avisos;
    }

    // =====================================================================
    // CONEXAO
    // =====================================================================

    private void escolherServidor() {
        while (true) {
            String digitado = ler("IP do servidor (Enter = 127.0.0.1): ").trim();
            ip = digitado.isEmpty() ? "127.0.0.1" : digitado;
            porta = lerPorta();
            if (conectar()) return;
            System.out.println("Tente novamente.\n");
        }
    }

    private int lerPorta() {
        while (true) {
            String linha = ler("Porta do servidor: ").trim();
            try {
                int p = Integer.parseInt(linha);
                if (p >= 1 && p <= 65535) return p;
            } catch (NumberFormatException ignorada) {
                // cai na mensagem abaixo
            }
            System.out.println("Porta invalida. Digite um numero entre 1 e 65535.");
        }
    }

    private boolean conectar() {
        fecharConexao();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, porta), TIMEOUT_CONEXAO_MS);
            s.setSoTimeout(TIMEOUT_RESPOSTA_MS);
            socket = s;
            entrada = new BufferedInputStream(s.getInputStream());
            saida = new BufferedOutputStream(s.getOutputStream());
            ultimaAtividade = System.currentTimeMillis();
            Log.print("[CONEXAO]", "Conectado ao servidor " + ip + ":" + porta);
            return true;
        } catch (UnknownHostException e) {
            Log.print("[ERRO]", "Endereco desconhecido: " + ip);
        } catch (SocketTimeoutException e) {
            Log.print("[ERRO]", "Tempo esgotado ao conectar em " + ip + ":" + porta
                    + " (IP errado ou firewall bloqueando?)");
        } catch (ConnectException e) {
            Log.print("[ERRO]", "Conexao recusada em " + ip + ":" + porta
                    + " (o servidor esta rodando nessa porta?)");
        } catch (IOException | IllegalArgumentException e) {
            Log.print("[ERRO]", "Nao foi possivel conectar: " + e.getMessage());
        }
        return false;
    }

    private boolean conectado() {
        return socket != null && !socket.isClosed();
    }

    private void fecharConexao() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignorada) {
                // nada a fazer
            }
            Log.print("[CONEXAO]", "Conexao com " + ip + ":" + porta + " encerrada.");
        }
        socket = null;
        entrada = null;
        saida = null;
    }

    // =====================================================================
    // AUXILIARES
    // =====================================================================

    private Map<String, Object> novaRequisicao(String op) {
        Map<String, Object> req = new LinkedHashMap<>(); // 'op' sempre como primeira chave
        req.put("op", op);
        return req;
    }

    /** Mostra a resposta do admin_list_users como tabela. */
    private void mostrarListaUsuarios(Map<String, Object> r) {
        Object lista = r.get("users");
        if (!(lista instanceof List)) return;
        List<?> itens = (List<?>) lista;
        System.out.println("    " + itens.size() + (itens.size() == 1 ? " usuario cadastrado:" : " usuarios cadastrados:"));
        System.out.println(String.format("    %-3s %-20s %-35s %s", "#", "USUARIO", "EMAIL", "ROLE"));
        int n = 0;
        for (Object item : itens) {
            n++;
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            System.out.println(String.format("    %-3d %-20s %-35s %s", n,
                    String.valueOf(m.get("user")), String.valueOf(m.get("email")), String.valueOf(m.get("role"))));
        }
    }

    private boolean exigirAdmin() {
        if (!exigirLogin()) return false;
        if (!"admin".equals(role)) {
            Log.print("[AVISO]", "Opcao so para administrador. Voce esta logado como '" + role + "'.");
            return false;
        }
        return true;
    }

    private boolean exigirLogin() {
        if (token == null) {
            Log.print("[AVISO]", "Voce nao esta logado (sem token). Faca login primeiro (opcao 2).");
            return false;
        }
        return true;
    }

    /** So avisa: o valor e enviado mesmo assim, para o servidor mostrar a validacao dele. */
    private void conferirLocal(String campo, String valor, Pattern regex, boolean vazioPermitido) {
        if (vazioPermitido && valor.isEmpty()) return;
        if (!regex.matcher(valor).matches()) {
            Log.print("[AVISO]", "'" + campo + "' nao segue o formato " + regex.pattern()
                    + " -> o servidor deve recusar (400). Enviando mesmo assim.");
        }
    }

    private static String texto(Map<String, Object> m, String chave) {
        Object v = m.get(chave);
        return v instanceof String ? (String) v : null;
    }

    private String ler(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            String linha = teclado.readLine();
            if (linha != null) return linha;
        } catch (IOException ignorada) {
            // trata abaixo
        }
        System.out.println("\nEntrada encerrada.");
        fecharConexao();
        System.exit(0);
        return "";
    }
}
