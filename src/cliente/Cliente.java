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
                case "7": listarSalas();     break;
                case "8": reservarSala();    break;
                case "9": minhasReservas();  break;
                case "10": adminListar();    break;
                case "11": adminVer();       break;
                case "12": adminAlterar();   break;
                case "13": adminRemover();   break;
                case "20": adminCriarSala();          break;
                case "21": adminVerSalasEReservas();  break;
                case "22": adminVerSala();            break;
                case "23": adminAlterarSala();        break;
                case "24": adminRemoverSala();        break;
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
        System.out.println(" 7 - Listar salas                 (list_rooms)");
        System.out.println(" 8 - Reservar uma sala            (create_reservation)");
        System.out.println(" 9 - Minhas reservas              (list_reservations)");
        System.out.println(" 0 - Sair");
        if ("admin".equals(role)) {
            System.out.println("------------------------ ADMINISTRADOR -----------------------");
            System.out.println(" 10 - Listar todos os usuarios    (admin_list_users)");
            System.out.println(" 11 - Ver um usuario              (admin_read_user)");
            System.out.println(" 12 - Alterar um usuario          (admin_update_user)");
            System.out.println(" 13 - Remover um usuario          (admin_delete_user)");
            System.out.println(" 20 - Cadastrar sala              (create_room)");
            System.out.println(" 21 - Ver todas as salas e reservas (list_rooms + list_reservations)");
            System.out.println(" 22 - Ver uma sala e sua agenda   (read_room + list_reservations)");
            System.out.println(" 23 - Alterar sala                (update_room)");
            System.out.println(" 24 - Remover sala                (delete_room)");
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

    // =====================================================================
    // SALAS E RESERVAS (qualquer usuario logado)
    // =====================================================================

    private void listarSalas() {
        System.out.println("\n--- LISTAR SALAS (list_rooms) ---");
        if (!exigirLogin()) return;
        System.out.println("Filtros opcionais (Enter = sem filtro).");
        String data = ler("Data para ver se esta livre (AAAA-MM-DD): ").trim();
        Map<String, Object> req = novaRequisicao("list_rooms");
        req.put("token", token);
        if (!data.isEmpty()) {
            String ini = ler("Hora de inicio (HH:MM): ").trim();
            String fim = ler("Hora de fim    (HH:MM): ").trim();
            conferirLocal("date", data, Protocolo.DATA, false);
            conferirLocal("start_time", ini, Protocolo.HORA, false);
            conferirLocal("end_time", fim, Protocolo.HORA, false);
            req.put("date", data);
            req.put("start_time", ini);
            req.put("end_time", fim);
        }
        String minimo = ler("Capacidade minima: ").trim();
        if (!minimo.isEmpty()) {
            conferirLocal("min_capacity", minimo, Protocolo.CAPACIDADE, false);
            req.put("min_capacity", minimo);
        }
        Map<String, Object> r = executar(req);
        if (sucesso(r)) mostrarSalas(lista(r, "rooms"), null);
    }

    private void reservarSala() {
        System.out.println("\n--- RESERVAR SALA (create_reservation) ---");
        if (!exigirLogin()) return;
        String sala = ler("Numero da sala (room_id): ").trim();
        String data = ler("Data (AAAA-MM-DD)       : ").trim();
        String ini = ler("Hora de inicio (HH:MM)  : ").trim();
        String fim = ler("Hora de fim    (HH:MM)  : ").trim();
        String assunto = ler("Assunto (sem acentos)   : ");
        String part = ler("Participantes           : ").trim();
        conferirLocal("room_id", sala, Protocolo.ID, false);
        conferirLocal("date", data, Protocolo.DATA, false);
        conferirLocal("start_time", ini, Protocolo.HORA, false);
        conferirLocal("end_time", fim, Protocolo.HORA, false);
        conferirLocal("topic", assunto, Protocolo.ASSUNTO, false);
        conferirLocal("participants", part, Protocolo.CAPACIDADE, false);
        Map<String, Object> req = novaRequisicao("create_reservation");
        req.put("token", token);
        req.put("room_id", sala);
        req.put("date", data);
        req.put("start_time", ini);
        req.put("end_time", fim);
        req.put("topic", assunto);
        req.put("participants", part);
        Map<String, Object> r = executar(req);
        if (sucesso(r)) System.out.println("    Numero da reserva: " + texto(r, "reservation_id"));
    }

    private void minhasReservas() {
        System.out.println("\n--- MINHAS RESERVAS (list_reservations, scope mine) ---");
        if (!exigirLogin()) return;
        Map<String, Object> req = novaRequisicao("list_reservations");
        req.put("token", token);
        req.put("scope", "mine");
        Map<String, Object> r = executar(req);
        if (sucesso(r)) mostrarReservas(lista(r, "reservations"), "    ");
    }

    // =====================================================================
    // SALAS - ADMINISTRADOR
    // =====================================================================

    private void adminCriarSala() {
        System.out.println("\n--- CADASTRAR SALA (create_room) ---");
        if (!exigirAdmin()) return;
        System.out.println("Sem acentos. Nome: letras, numeros, espaco e hifen (1 a 30). Local: tambem virgula e ponto (1 a 60).");
        String nome = ler("Nome da sala  : ");
        String cap = ler("Capacidade    : ").trim();
        String local = ler("Localizacao   : ");
        String rec = ler("Recursos (separados por virgula, Enter ou - = nenhum): ");
        conferirLocal("name", nome, Protocolo.NOME_SALA, false);
        conferirLocal("capacity", cap, Protocolo.CAPACIDADE, false);
        conferirLocal("location", local, Protocolo.LOCAL, false);
        Map<String, Object> req = novaRequisicao("create_room");
        req.put("token", token);
        req.put("name", nome);
        req.put("capacity", cap);
        req.put("location", local);
        req.put("resources", separarRecursos(rec));
        Map<String, Object> r = executar(req);
        if (sucesso(r)) System.out.println("    Numero da sala criada (room_id): " + texto(r, "room_id"));
    }

    /** Item e/m da EP-2: todas as salas com status e, para cada uma, quem reservou e quando. */
    private void adminVerSalasEReservas() {
        System.out.println("\n--- TODAS AS SALAS E RESERVAS (list_rooms + list_reservations scope all) ---");
        if (!exigirAdmin()) return;
        Map<String, Object> req = novaRequisicao("list_rooms");
        req.put("token", token);
        Map<String, Object> salas = executar(req);
        if (!sucesso(salas)) return;
        Map<String, Object> req2 = novaRequisicao("list_reservations");
        req2.put("token", token);
        req2.put("scope", "all");
        Map<String, Object> reservas = executar(req2);
        if (!sucesso(reservas)) return;
        mostrarSalas(lista(salas, "rooms"), lista(reservas, "reservations"));
    }

    private void adminVerSala() {
        System.out.println("\n--- VER UMA SALA E SUA AGENDA (read_room + list_reservations) ---");
        if (!exigirAdmin()) return;
        String sala = ler("Numero da sala (room_id): ").trim();
        conferirLocal("room_id", sala, Protocolo.ID, false);
        Map<String, Object> req = novaRequisicao("read_room");
        req.put("token", token);
        req.put("room_id", sala);
        Map<String, Object> r = executar(req);
        if (!sucesso(r)) return;
        Map<String, Object> req2 = novaRequisicao("list_reservations");
        req2.put("token", token);
        req2.put("scope", "all");
        req2.put("room_id", sala);
        Map<String, Object> reservas = executar(req2);
        if (!sucesso(reservas)) return;
        List<Object> uma = new ArrayList<>();
        uma.add(r);
        mostrarSalas(uma, lista(reservas, "reservations"));
    }

    private void adminAlterarSala() {
        System.out.println("\n--- ALTERAR SALA (update_room) ---");
        if (!exigirAdmin()) return;
        String sala = ler("Numero da sala (room_id): ").trim();
        conferirLocal("room_id", sala, Protocolo.ID, false);
        // Le a sala antes: 'resources' e sempre enviado como lista completa, entao precisamos da lista atual
        Map<String, Object> leitura = novaRequisicao("read_room");
        leitura.put("token", token);
        leitura.put("room_id", sala);
        Map<String, Object> atual = executar(leitura);
        if (!sucesso(atual)) return;
        List<Object> uma = new ArrayList<>();
        uma.add(atual);
        mostrarSalas(uma, null);

        System.out.println("Deixe em branco (so Enter) o que NAO quiser alterar.");
        String nome = ler("Novo nome        : ");
        String cap = ler("Nova capacidade  : ").trim();
        String local = ler("Nova localizacao : ");
        String rec = ler("Novos recursos (separados por virgula, '-' = nenhum): ");
        String st = ler("Novo status (active = livre, inactive = indisponivel): ").trim().toLowerCase();
        if (st.equals("livre")) st = "active";
        if (st.equals("indisponivel")) st = "inactive";
        conferirLocal("name", nome, Protocolo.NOME_SALA, true);
        conferirLocal("capacity", cap, Protocolo.CAPACIDADE, true);
        conferirLocal("location", local, Protocolo.LOCAL, true);
        conferirLocal("room_status", st, Protocolo.ROOM_STATUS, true);

        List<Object> recursos;
        if (rec.trim().isEmpty()) {
            recursos = lista(atual, "resources"); // mantem os atuais
        } else if (rec.trim().equals("-")) {
            recursos = new ArrayList<>();
        } else {
            recursos = separarRecursos(rec);
        }
        Map<String, Object> req = novaRequisicao("update_room");
        req.put("token", token);
        req.put("room_id", sala);
        req.put("name", nome);
        req.put("capacity", cap);
        req.put("location", local);
        req.put("resources", recursos);
        req.put("room_status", st);
        executar(req);
    }

    private void adminRemoverSala() {
        System.out.println("\n--- REMOVER SALA (delete_room) ---");
        if (!exigirAdmin()) return;
        String sala = ler("Numero da sala (room_id): ").trim();
        String certeza = ler("Tem certeza que deseja remover a sala " + sala + "? (s/n): ").trim().toLowerCase();
        if (!certeza.equals("s")) {
            System.out.println("Cancelado. Nada foi enviado.");
            return;
        }
        conferirLocal("room_id", sala, Protocolo.ID, false);
        Map<String, Object> req = novaRequisicao("delete_room");
        req.put("token", token);
        req.put("room_id", sala);
        Map<String, Object> r = executar(req);
        if (r != null && "409".equals(texto(r, "status"))) {
            Log.print("[INFO]", "A sala tem reservas futuras. Para tira-la de uso, altere o status para 'inactive' (opcao 23).");
        }
    }

    // ---------------------------------------------------------------- exibicao de salas e reservas

    /**
     * Mostra as salas. Se 'reservas' nao for null, mostra embaixo de cada sala quem reservou e quando.
     */
    private void mostrarSalas(List<Object> salas, List<Object> reservas) {
        if (salas.isEmpty()) {
            System.out.println("    Nenhuma sala cadastrada.");
            return;
        }
        for (Object o : salas) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> s = (Map<?, ?>) o;
            String st = String.valueOf(s.get("room_status"));
            String stTexto = "active".equals(st) ? "livre (active)" : "inactive".equals(st) ? "indisponivel (inactive)" : st;
            System.out.println("    ----------------------------------------------------------");
            System.out.println("    Sala " + s.get("room_id") + ": " + s.get("name"));
            System.out.println("      Capacidade : " + s.get("capacity") + " lugares");
            System.out.println("      Local      : " + s.get("location"));
            System.out.println("      Recursos   : " + juntar(s.get("resources")));
            System.out.println("      Status     : " + stTexto);
            if (s.containsKey("available")) {
                System.out.println("      No horario pedido: " + ("true".equals(s.get("available")) ? "LIVRE" : "OCUPADA/INDISPONIVEL"));
            }
            if (reservas != null) {
                List<Object> daSala = new ArrayList<>();
                for (Object r : reservas) {
                    if (r instanceof Map && String.valueOf(((Map<?, ?>) r).get("room_id")).equals(String.valueOf(s.get("room_id")))) {
                        daSala.add(r);
                    }
                }
                if (daSala.isEmpty()) {
                    System.out.println("      Reservas   : nenhuma");
                } else {
                    System.out.println("      Reservas   :");
                    mostrarReservas(daSala, "        ");
                }
            }
        }
        System.out.println("    ----------------------------------------------------------");
    }

    private void mostrarReservas(List<Object> reservas, String recuo) {
        if (reservas.isEmpty()) {
            System.out.println(recuo + "Nenhuma reserva.");
            return;
        }
        System.out.println(String.format("%s%-4s %-20s %-12s %-10s %-13s %-5s %s", recuo,
                "#", "SALA", "QUEM", "DATA", "HORARIO", "PESS.", "ASSUNTO"));
        for (Object o : reservas) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> r = (Map<?, ?>) o;
            System.out.println(String.format("%s%-4s %-20s %-12s %-10s %-13s %-5s %s", recuo,
                    r.get("reservation_id"), r.get("room_id") + " " + r.get("name"), r.get("user"), r.get("date"),
                    r.get("start_time") + "-" + r.get("end_time"), r.get("participants"), r.get("topic")));
        }
    }

    private static String juntar(Object lista) {
        if (!(lista instanceof List) || ((List<?>) lista).isEmpty()) return "nenhum";
        List<String> partes = new ArrayList<>();
        for (Object o : (List<?>) lista) partes.add(String.valueOf(o));
        return String.join(", ", partes);
    }

    /** "projetor, tv" -> ["projetor", "tv"]; vazio ou "-" -> [] (nenhum recurso). */
    private static List<Object> separarRecursos(String texto) {
        List<Object> lista = new ArrayList<>();
        if (texto.trim().equals("-")) return lista;
        for (String parte : texto.split(",")) {
            if (!parte.trim().isEmpty()) lista.add(parte.trim());
        }
        return lista;
    }

    private static boolean sucesso(Map<String, Object> r) {
        if (r == null) return false;
        String st = texto(r, "status");
        return st != null && st.startsWith("2");
    }

    private static List<Object> lista(Map<String, Object> r, String chave) {
        Object v = r.get(chave);
        List<Object> l = new ArrayList<>();
        if (v instanceof List) l.addAll((List<?>) v);
        return l;
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
        boolean opDeAdmin = op.startsWith("admin_") || op.equals("create_room") || op.equals("update_room")
                || op.equals("delete_room");
        if (status.equals("403") && opDeAdmin && usouMeuToken
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
        boolean cria = "register".equals(opEnviada) || "create_room".equals(opEnviada)
                || "create_reservation".equals(opEnviada);
        if (opEnviada != null && cria && "200".equals(status)) {
            avisos.add("'" + opEnviada + "' com sucesso deveria responder status 201");
        }
        if (opEnviada != null && !cria && "201".equals(status)) {
            avisos.add("'" + opEnviada + "' com sucesso deveria responder status 200");
        }
        if ("201".equals(status) && "create_room".equals(opEnviada) && !Protocolo.casa(Protocolo.ID, texto(r, "room_id"))) {
            avisos.add("falta 'room_id' (string numerica)");
        }
        if ("201".equals(status) && "create_reservation".equals(opEnviada)
                && !Protocolo.casa(Protocolo.ID, texto(r, "reservation_id"))) {
            avisos.add("falta 'reservation_id' (string numerica)");
        }
        if ("200".equals(status) && "list_rooms".equals(opEnviada)) {
            conferirLista(r, "rooms", new String[] {"room_id", "name", "capacity", "location", "room_status"}, avisos);
        }
        if ("200".equals(status) && "list_reservations".equals(opEnviada)) {
            conferirLista(r, "reservations", new String[] {"reservation_id", "room_id", "name", "user", "date",
                "start_time", "end_time", "topic", "participants"}, avisos);
        }
        if ("200".equals(status) && "read_room".equals(opEnviada)) {
            for (String campo : new String[] {"room_id", "name", "capacity", "location", "room_status"}) {
                if (texto(r, campo) == null) avisos.add("falta '" + campo + "'");
            }
            if (!(r.get("resources") instanceof List)) avisos.add("falta o array 'resources'");
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

    /** Confere 'count' e os campos de cada item de uma listagem. */
    private static void conferirLista(Map<String, Object> r, String chave, String[] campos, List<String> avisos) {
        Object lista = r.get(chave);
        if (!(lista instanceof List)) {
            avisos.add("falta o array '" + chave + "'");
            return;
        }
        List<?> itens = (List<?>) lista;
        if (!String.valueOf(itens.size()).equals(texto(r, "count"))) {
            avisos.add("'count' (" + texto(r, "count") + ") diferente da quantidade de itens em '" + chave + "' (" + itens.size() + ")");
        }
        for (Object item : itens) {
            if (!(item instanceof Map)) {
                avisos.add("item de '" + chave + "' nao e objeto");
                return;
            }
            for (String campo : campos) {
                if (!(((Map<?, ?>) item).get(campo) instanceof String)) {
                    avisos.add("item de '" + chave + "' sem '" + campo + "' (string)");
                    return;
                }
            }
        }
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
