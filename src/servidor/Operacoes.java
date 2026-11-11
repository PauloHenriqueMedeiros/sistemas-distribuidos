package servidor;

import comum.Aulas;
import comum.Json;
import comum.Log;
import comum.Protocolo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recebe o texto de uma requisicao e devolve o objeto de resposta,
 * seguindo o protocolo v2.9.1 da turma.
 *
 * Ordem de validacao (obrigatoria desde a v2.9, secao 4 do contrato):
 *   tamanho/JSON/op (error) -> formato (400) -> nada para atualizar (400) -> token (401) -> perfil (403)
 *   -> nao existe (404) -> dono/senha/ultimo admin (403) -> reserva encerrada (409)
 *   -> participantes/horario resultante (400) -> conflitos (409) -> executa
 */
public final class Operacoes {

    private final BancoDados banco;
    private final String origem; // identificacao da conexao, usada no log

    public Operacoes(BancoDados banco, String origem) {
        this.banco = banco;
        this.origem = origem;
    }

    // =====================================================================
    // ENTRADA
    // =====================================================================

    @SuppressWarnings("unchecked")
    public Map<String, Object> processar(String texto) {
        Object lido;
        try {
            lido = Json.ler(texto);
        } catch (Json.ErroJson e) {
            nota("RECUSADO: JSON invalido (" + e.getMessage() + ")");
            return erroProtocolo("Requisicao invalida");
        }
        if (!(lido instanceof Map)) {
            nota("RECUSADO: a mensagem nao e um objeto JSON { ... }");
            return erroProtocolo("Requisicao invalida");
        }
        Map<String, Object> req = (Map<String, Object>) lido;

        Object opObj = req.get("op");
        if (!(opObj instanceof String)) {
            nota("RECUSADO: chave 'op' ausente, nula ou nao e string");
            return erroProtocolo("Requisicao invalida");
        }
        String op = (String) opObj;

        try {
            switch (op) {
                case "register":    return register(req);
                case "login":       return login(req);
                case "logout":      return logout(req);
                case "read_user":   return readUser(req);
                case "update_user": return updateUser(req);
                case "delete_user": return deleteUser(req);
                case "admin_list_users":  return adminListUsers(req);
                case "admin_read_user":   return adminReadUser(req);
                case "admin_update_user": return adminUpdateUser(req);
                case "admin_delete_user": return adminDeleteUser(req);
                case "create_room":        return createRoom(req);
                case "list_rooms":         return listRooms(req);
                case "read_room":          return readRoom(req);
                case "update_room":        return updateRoom(req);
                case "delete_room":        return deleteRoom(req);
                case "check_availability": return checkAvailability(req);
                case "create_reservation": return createReservation(req);
                case "list_reservations":  return listReservations(req);
                case "read_reservation":   return readReservation(req);
                case "update_reservation": return updateReservation(req);
                case "delete_reservation": return deleteReservation(req);
                default:
                    nota("RECUSADO: operacao desconhecida '" + op + "'");
                    return erroProtocolo("Operacao desconhecida");
            }
        } catch (RuntimeException e) {
            nota("ERRO INTERNO ao processar '" + op + "': " + e);
            return resposta(op + "_response", "500", "Erro interno do servidor");
        }
    }

    // =====================================================================
    // AUTENTICACAO
    // =====================================================================

    private Map<String, Object> register(Map<String, Object> req) {
        final String OP = "register_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "email", Protocolo.EMAIL, false, problemas);
        checar(req, "user", Protocolo.USER, false, problemas);
        checar(req, "password", Protocolo.PASSWORD, false, problemas);
        if (!problemas.isEmpty()) {
            nota("register RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados de cadastro em formato invalido");
        }
        String email = (String) req.get("email");
        String user = (String) req.get("user");
        String password = (String) req.get("password");

        synchronized (banco) {
            boolean userExiste = banco.existeUser(user);
            boolean emailExiste = banco.existeEmail(email);
            if (userExiste || emailExiste) {
                List<String> motivos = new ArrayList<>();
                if (userExiste) motivos.add("user '" + user + "' ja existe");
                if (emailExiste) motivos.add("email '" + email + "' ja existe");
                nota("register RECUSADO (409): " + String.join(" e ", motivos));
                return resposta(OP, "409", "Usuario ou email ja cadastrado");
            }
            banco.criarUsuario(email, user, password, "user");
        }
        nota("register OK (201): usuario '" + user + "' <" + email + "> cadastrado com role 'user'");
        return resposta(OP, "201", "Usuario cadastrado com sucesso");
    }

    /**
     * Regra 3.12 (v2.2): ordem obrigatoria formato (400) -> credenciais (401) -> sessao ativa (409).
     * Regra 3.11 (v2.2): checar a sessao ativa e criar o token na mesma secao critica.
     */
    private Map<String, Object> login(Map<String, Object> req) {
        final String OP = "login_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "email", Protocolo.EMAIL, false, problemas);
        checar(req, "password", Protocolo.PASSWORD, false, problemas);
        if (!problemas.isEmpty()) {
            nota("login RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Email ou senha em formato invalido");
        }
        String email = (String) req.get("email");
        String password = (String) req.get("password");

        synchronized (banco) {
            BancoDados.Usuario u = banco.buscarPorEmail(email);
            if (u == null || !u.password.equals(password)) {
                // mesma mensagem nos dois casos, para nao revelar quais usuarios existem
                nota("login RECUSADO (401): " + (u == null ? "email nao cadastrado" : "senha incorreta"));
                return resposta(OP, "401", "Email ou senha incorretos");
            }
            if (banco.temSessaoAtiva(email)) {
                nota("login RECUSADO (409): '" + u.user + "' ja possui sessao ativa");
                return resposta(OP, "409", "Usuario ja possui sessao ativa");
            }
            String token = banco.criarSessao(email);
            nota("login OK (200): '" + u.user + "' (role " + u.role + ") recebeu token " + token
                    + ("admin".equals(u.role) ? " (token entrou na whitelist de admin)" : ""));
            Map<String, Object> r = resposta(OP, "200", "Login realizado com sucesso");
            r.put("token", token);
            r.put("role", u.role);
            return r;
        }
    }

    private Map<String, Object> logout(Map<String, Object> req) {
        final String OP = "logout_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        if (!problemas.isEmpty()) {
            nota("logout RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Token em formato invalido");
        }
        String token = (String) req.get("token");

        synchronized (banco) {
            BancoDados.Usuario u = banco.autenticar(token);
            if (u == null) {
                nota("logout RECUSADO (401): token inexistente, invalidado ou expirado");
                return resposta(OP, "401", "Token invalido ou expirado");
            }
            banco.encerrarSessao(token);
            nota("logout OK (200): sessao de '" + u.user + "' encerrada, token invalidado");
        }
        return resposta(OP, "200", "Logout realizado com sucesso");
    }

    // =====================================================================
    // CRUD DO PROPRIO CADASTRO
    // =====================================================================

    private Map<String, Object> readUser(Map<String, Object> req) {
        final String OP = "read_user_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        if (!problemas.isEmpty()) {
            nota("read_user RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Token em formato invalido");
        }
        String token = (String) req.get("token");

        synchronized (banco) {
            BancoDados.Usuario u = banco.autenticar(token);
            if (u == null) {
                nota("read_user RECUSADO (401): token inexistente, invalidado ou expirado");
                return resposta(OP, "401", "Token invalido ou expirado");
            }
            Map<String, Object> r = resposta(OP, "200", "Consulta realizada com sucesso");
            r.put("user", u.user);
            r.put("email", u.email);
            r.put("role", u.role);
            r.put("created_at", u.createdAt);
            nota("read_user OK (200): dados de '" + u.user + "' enviados");
            return r;
        }
    }

    private Map<String, Object> updateUser(Map<String, Object> req) {
        final String OP = "update_user_response";
        List<String> problemas = new ArrayList<>();
        if (req.containsKey("email")) {
            problemas.add("'email' nao pode ser alterado (a chave nao pode ser enviada)");
        }
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "user", Protocolo.USER, true, problemas);          // "" = nao alterar
        checar(req, "password", Protocolo.PASSWORD, true, problemas);  // "" = nao alterar
        if (!problemas.isEmpty()) {
            nota("update_user RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        String token = (String) req.get("token");
        String novoUser = (String) req.get("user");
        String novaSenha = (String) req.get("password");

        // Regra 2.13 (v2.4): pelo menos um campo precisa vir preenchido
        if (novoUser.isEmpty() && novaSenha.isEmpty()) {
            nota("update_user RECUSADO (400): 'user' e 'password' vazios, nada para atualizar");
            return resposta(OP, "400", "Nenhum dado para atualizar");
        }

        synchronized (banco) {
            BancoDados.Usuario u = banco.autenticar(token);
            if (u == null) {
                nota("update_user RECUSADO (401): token inexistente, invalidado ou expirado");
                return resposta(OP, "401", "Token invalido ou expirado");
            }
            if (!novoUser.isEmpty() && !novoUser.equals(u.user) && banco.existeUser(novoUser)) {
                nota("update_user RECUSADO (409): user '" + novoUser + "' ja pertence a outra pessoa");
                return resposta(OP, "409", "Usuario ja esta em uso");
            }
            List<String> mudancas = new ArrayList<>();
            if (!novoUser.isEmpty() && !novoUser.equals(u.user)) {
                mudancas.add("user '" + u.user + "' -> '" + novoUser + "'");
                u.user = novoUser;
            }
            if (!novaSenha.isEmpty()) {
                mudancas.add("senha alterada");
                u.password = novaSenha;
            }
            nota("update_user OK (200): " + (mudancas.isEmpty()
                    ? "valores iguais aos atuais, nada mudou" : String.join(", ", mudancas)));
        }
        return resposta(OP, "200", "Dados atualizados com sucesso");
    }

    private Map<String, Object> deleteUser(Map<String, Object> req) {
        final String OP = "delete_user_response";
        List<String> problemasToken = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemasToken);
        if (!problemasToken.isEmpty()) {
            nota("delete_user RECUSADO (400): " + problemasToken);
            return resposta(OP, "400", "Token em formato invalido");
        }
        List<String> problemasSenha = new ArrayList<>();
        checar(req, "password", Protocolo.PASSWORD, false, problemasSenha);
        if (!problemasSenha.isEmpty()) {
            nota("delete_user RECUSADO (400): " + problemasSenha);
            return resposta(OP, "400", "Senha em formato invalido");
        }
        String token = (String) req.get("token");
        String password = (String) req.get("password");

        synchronized (banco) {
            BancoDados.Usuario u = banco.autenticar(token);
            if (u == null) {
                nota("delete_user RECUSADO (401): token inexistente, invalidado ou expirado");
                return resposta(OP, "401", "Token invalido ou expirado");
            }
            if (!u.password.equals(password)) {
                // v2.1 (regra 3.9): senha errada com token valido e 403; nada e removido e a sessao continua
                nota("delete_user RECUSADO (403): senha de confirmacao incorreta, sessao de '" + u.user + "' continua ativa");
                return resposta(OP, "403", "Senha incorreta");
            }
            if ("admin".equals(u.role) && banco.contarAdmins() <= 1) {
                nota("delete_user RECUSADO (403): '" + u.user + "' e o ultimo administrador");
                return resposta(OP, "403", "Nao e possivel remover o ultimo administrador");
            }
            int apagadas = banco.removerUsuario(u.email);
            nota("delete_user OK (200): usuario '" + u.user + "' <" + u.email + "> removido, token invalidado, "
                    + apagadas + " reserva(s) apagada(s); user e email livres para novo cadastro");
        }
        return resposta(OP, "200", "Usuario removido com sucesso");
    }

    // =====================================================================
    // OPERACOES DO ADMINISTRADOR (exigem token de role "admin", regra 3.7)
    // =====================================================================

    /** Lista todos os cadastros (sem senha). */
    private Map<String, Object> adminListUsers(Map<String, Object> req) {
        final String OP = "admin_list_users_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        if (!problemas.isEmpty()) {
            nota("admin_list_users RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Token em formato invalido");
        }
        synchronized (banco) {
            Map<String, Object> negado = exigirAdmin(OP, "admin_list_users", (String) req.get("token"));
            if (negado != null) return negado;

            List<Object> lista = new ArrayList<>();
            for (BancoDados.Usuario u : banco.listarUsuarios()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("user", u.user);
                item.put("email", u.email);
                item.put("role", u.role);
                lista.add(item);
            }
            Map<String, Object> r = resposta(OP, "200", "Consulta realizada com sucesso");
            r.put("count", String.valueOf(lista.size()));
            r.put("users", lista);
            nota("admin_list_users OK (200): " + lista.size() + " cadastro(s) enviados");
            return r;
        }
    }

    /** Le o cadastro de qualquer usuario (target_user). */
    private Map<String, Object> adminReadUser(Map<String, Object> req) {
        final String OP = "admin_read_user_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "target_user", Protocolo.USER, false, problemas);
        if (!problemas.isEmpty()) {
            nota("admin_read_user RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        String alvo = (String) req.get("target_user");
        synchronized (banco) {
            Map<String, Object> negado = exigirAdmin(OP, "admin_read_user", (String) req.get("token"));
            if (negado != null) return negado;

            BancoDados.Usuario u = banco.buscarPorUser(alvo);
            if (u == null) {
                nota("admin_read_user RECUSADO (404): usuario '" + alvo + "' nao existe");
                return resposta(OP, "404", "Usuario nao encontrado");
            }
            Map<String, Object> r = resposta(OP, "200", "Consulta realizada com sucesso");
            r.put("user", u.user);
            r.put("email", u.email);
            r.put("role", u.role);
            r.put("created_at", u.createdAt);
            nota("admin_read_user OK (200): dados de '" + u.user + "' enviados");
            return r;
        }
    }

    /** Altera user, senha e/ou role de qualquer usuario. "" = nao alterar; email proibido. */
    private Map<String, Object> adminUpdateUser(Map<String, Object> req) {
        final String OP = "admin_update_user_response";
        List<String> problemas = new ArrayList<>();
        if (req.containsKey("email")) {
            problemas.add("'email' nao pode ser alterado (a chave nao pode ser enviada)");
        }
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "target_user", Protocolo.USER, false, problemas);
        checar(req, "user", Protocolo.USER, true, problemas);          // "" = nao alterar
        checar(req, "password", Protocolo.PASSWORD, true, problemas);  // "" = nao alterar
        checar(req, "role", Protocolo.ROLE, true, problemas);          // "" = nao alterar
        if (!problemas.isEmpty()) {
            nota("admin_update_user RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        String alvo = (String) req.get("target_user");
        String novoUser = (String) req.get("user");
        String novaSenha = (String) req.get("password");
        String novoRole = (String) req.get("role");

        // Regra 2.13 (v2.4): pelo menos um campo precisa vir preenchido
        if (novoUser.isEmpty() && novaSenha.isEmpty() && novoRole.isEmpty()) {
            nota("admin_update_user RECUSADO (400): 'user', 'password' e 'role' vazios, nada para atualizar");
            return resposta(OP, "400", "Nenhum dado para atualizar");
        }

        synchronized (banco) {
            Map<String, Object> negado = exigirAdmin(OP, "admin_update_user", (String) req.get("token"));
            if (negado != null) return negado;

            BancoDados.Usuario u = banco.buscarPorUser(alvo);
            if (u == null) {
                nota("admin_update_user RECUSADO (404): usuario '" + alvo + "' nao existe");
                return resposta(OP, "404", "Usuario nao encontrado");
            }
            if ("admin".equals(u.role) && "user".equals(novoRole) && banco.contarAdmins() <= 1) {
                nota("admin_update_user RECUSADO (403): '" + u.user + "' e o ultimo administrador, nao pode virar 'user'");
                return resposta(OP, "403", "Nao e possivel remover o ultimo administrador");
            }
            if (!novoUser.isEmpty() && !novoUser.equals(u.user) && banco.existeUser(novoUser)) {
                nota("admin_update_user RECUSADO (409): user '" + novoUser + "' ja pertence a outra pessoa");
                return resposta(OP, "409", "Usuario ja esta em uso");
            }
            List<String> mudancas = new ArrayList<>();
            String nomeAntes = u.user;
            if (!novoUser.isEmpty() && !novoUser.equals(u.user)) {
                mudancas.add("user '" + u.user + "' -> '" + novoUser + "'");
                u.user = novoUser;
            }
            if (!novaSenha.isEmpty()) {
                mudancas.add("senha alterada");
                u.password = novaSenha;
            }
            if (!novoRole.isEmpty() && !novoRole.equals(u.role)) {
                mudancas.add("role '" + u.role + "' -> '" + novoRole + "'"
                        + ("admin".equals(novoRole) ? " (tokens ativos entraram na whitelist)" : " (tokens ativos sairam da whitelist)"));
                banco.alterarRole(u, novoRole); // regra 3.14: vale na hora, sem novo login
            }
            nota("admin_update_user OK (200) em '" + nomeAntes + "': " + (mudancas.isEmpty()
                    ? "valores iguais aos atuais, nada mudou" : String.join(", ", mudancas)));
        }
        return resposta(OP, "200", "Dados atualizados com sucesso");
    }

    /** Remove qualquer usuario: cadastro e sessao (regra 3.13). */
    private Map<String, Object> adminDeleteUser(Map<String, Object> req) {
        final String OP = "admin_delete_user_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "target_user", Protocolo.USER, false, problemas);
        if (!problemas.isEmpty()) {
            nota("admin_delete_user RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        String alvo = (String) req.get("target_user");
        synchronized (banco) {
            Map<String, Object> negado = exigirAdmin(OP, "admin_delete_user", (String) req.get("token"));
            if (negado != null) return negado;

            BancoDados.Usuario u = banco.buscarPorUser(alvo);
            if (u == null) {
                nota("admin_delete_user RECUSADO (404): usuario '" + alvo + "' nao existe");
                return resposta(OP, "404", "Usuario nao encontrado");
            }
            if ("admin".equals(u.role) && banco.contarAdmins() <= 1) {
                nota("admin_delete_user RECUSADO (403): '" + u.user + "' e o ultimo administrador");
                return resposta(OP, "403", "Nao e possivel remover o ultimo administrador");
            }
            int apagadas = banco.removerUsuario(u.email);
            nota("admin_delete_user OK (200): usuario '" + u.user + "' <" + u.email + "> removido, sessao encerrada, "
                    + apagadas + " reserva(s) apagada(s); user e email livres para novo cadastro");
        }
        return resposta(OP, "200", "Usuario removido com sucesso");
    }

    /**
     * Confere o token (401) e se ele esta na whitelist de admin (403, regra 3.14).
     * Devolve a resposta de erro, ou null se o autor e admin. Chamar dentro de synchronized (banco).
     */
    private Map<String, Object> exigirAdmin(String opResposta, String op, String token) {
        BancoDados.Usuario autor = banco.autenticar(token);
        if (autor == null) {
            nota(op + " RECUSADO (401): token inexistente, invalidado ou expirado");
            return resposta(opResposta, "401", "Token invalido ou expirado");
        }
        if (!banco.tokenEhAdmin(token)) {
            nota(op + " RECUSADO (403): o token de '" + autor.user + "' nao esta na whitelist de admin");
            return resposta(opResposta, "403", "Permissao insuficiente");
        }
        return null;
    }

    // =====================================================================
    // SALAS (criar, alterar e remover exigem admin; ler e listar: qualquer usuario logado)
    // =====================================================================

    private Map<String, Object> createRoom(Map<String, Object> req) {
        final String OP = "create_room_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "name", Protocolo.NOME_SALA, false, problemas);
        checar(req, "capacity", Protocolo.CAPACIDADE, false, problemas);
        checar(req, "location", Protocolo.LOCAL, false, problemas);
        List<String> recursos = checarRecursos(req, true, false, problemas);
        List<String> slots = checarSlots(req, true, problemas);
        if (!problemas.isEmpty()) {
            nota("create_room RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados da sala em formato invalido");
        }
        String name = (String) req.get("name");
        int capacity = Integer.parseInt((String) req.get("capacity"));
        String location = (String) req.get("location");

        synchronized (banco) {
            Map<String, Object> negado = exigirAdmin(OP, "create_room", (String) req.get("token"));
            if (negado != null) return negado;
            if (banco.existeNomeSala(name, -1)) {
                nota("create_room RECUSADO (409): ja existe uma sala chamada '" + name + "'");
                return resposta(OP, "409", "Sala ja cadastrada");
            }
            BancoDados.Sala sala = banco.criarSala(name, capacity, location, recursos, slots);
            nota("create_room OK (201): sala " + sala.id + " '" + name + "' (" + capacity + " lugares, " + location
                    + ", recursos " + recursos + ", horarios " + Aulas.resumo(slots) + ") criada como 'active'");
            Map<String, Object> r = resposta(OP, "201", "Sala cadastrada com sucesso");
            r.put("room_id", String.valueOf(sala.id));
            return r;
        }
    }

    private Map<String, Object> listRooms(Map<String, Object> req) {
        final String OP = "list_rooms_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checarOpcional(req, "date", Protocolo.DATA, problemas);
        checarOpcional(req, "start_time", Protocolo.HORA, problemas);
        checarOpcional(req, "end_time", Protocolo.HORA, problemas);
        checarOpcional(req, "min_capacity", Protocolo.CAPACIDADE, problemas);
        int presentes = (req.containsKey("date") ? 1 : 0) + (req.containsKey("start_time") ? 1 : 0)
                + (req.containsKey("end_time") ? 1 : 0);
        if (presentes != 0 && presentes != 3) {
            problemas.add("date, start_time e end_time vem juntos ou nenhum deles");
        }
        Periodo periodo = null;
        if (problemas.isEmpty() && presentes == 3) {
            periodo = lerPeriodo((String) req.get("date"), (String) req.get("start_time"), (String) req.get("end_time"), problemas);
        }
        if (!problemas.isEmpty()) {
            nota("list_rooms RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Filtros em formato invalido");
        }
        int minimo = req.containsKey("min_capacity") ? Integer.parseInt((String) req.get("min_capacity")) : 0;

        synchronized (banco) {
            BancoDados.Usuario autor = banco.autenticar((String) req.get("token"));
            if (autor == null) return nao401(OP, "list_rooms");

            List<Object> salas = new ArrayList<>();
            for (BancoDados.Sala s : banco.listarSalas()) {
                if (s.capacity < minimo) continue;
                Map<String, Object> item = dadosSala(s);
                if (periodo != null) {
                    item.put("available", String.valueOf(motivoIndisponivel(s, periodo, -1) == null));
                }
                salas.add(item);
            }
            Map<String, Object> r = resposta(OP, "200", "Consulta realizada com sucesso");
            r.put("count", String.valueOf(salas.size()));
            r.put("rooms", salas);
            nota("list_rooms OK (200): " + salas.size() + " sala(s) enviada(s)"
                    + (periodo != null ? ", com disponibilidade em " + periodo : "")
                    + (minimo > 0 ? ", capacidade minima " + minimo : ""));
            return r;
        }
    }

    /** Com 'date' (opcional), devolve tambem a agenda da sala nesse dia (regra 8.5). */
    private Map<String, Object> readRoom(Map<String, Object> req) {
        final String OP = "read_room_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "room_id", Protocolo.ID, false, problemas);
        checarOpcional(req, "date", Protocolo.DATA, problemas);
        LocalDate data = null;
        if (problemas.isEmpty() && req.containsKey("date")) {
            data = lerData((String) req.get("date"), problemas);
        }
        if (!problemas.isEmpty()) {
            nota("read_room RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        synchronized (banco) {
            String token = (String) req.get("token");
            BancoDados.Usuario autor = banco.autenticar(token);
            if (autor == null) return nao401(OP, "read_room");
            BancoDados.Sala s = banco.buscarSala(Long.parseLong((String) req.get("room_id")));
            if (s == null) {
                nota("read_room RECUSADO (404): sala " + req.get("room_id") + " nao existe");
                return resposta(OP, "404", "Sala nao encontrada");
            }
            Map<String, Object> r = resposta(OP, "200", "Consulta realizada com sucesso");
            r.putAll(dadosSala(s));
            if (data != null) {
                List<Object> agenda = montarAgenda(s, data, autor, banco.tokenEhAdmin(token));
                r.put("date", data.toString());
                r.put("agenda", agenda);
                nota("read_room OK (200): sala " + s.id + " '" + s.name + "' com a agenda de " + data + " ("
                        + agenda.size() + " horario(s) da sala nesse dia)");
            } else {
                nota("read_room OK (200): dados da sala " + s.id + " '" + s.name + "' enviados");
            }
            return r;
        }
    }

    /**
     * update_room: "" nos campos de texto = nao alterar. resources: ausente ou [] mantem, ["0"] apaga todos,
     * lista nova substitui (regra 9.7). slots: ausente ou [] mantem, lista nova substitui (regra 8.7).
     */
    private Map<String, Object> updateRoom(Map<String, Object> req) {
        final String OP = "update_room_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "room_id", Protocolo.ID, false, problemas);
        checar(req, "name", Protocolo.NOME_SALA, true, problemas);
        checar(req, "capacity", Protocolo.CAPACIDADE, true, problemas);
        checar(req, "location", Protocolo.LOCAL, true, problemas);
        checar(req, "room_status", Protocolo.ROOM_STATUS, true, problemas);
        List<String> recursos = checarRecursos(req, false, true, problemas); // null = manter
        List<String> slots = checarSlots(req, false, problemas);              // null = manter
        if (!problemas.isEmpty()) {
            nota("update_room RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados da sala em formato invalido");
        }
        String name = (String) req.get("name");
        String capacidade = (String) req.get("capacity");
        String location = (String) req.get("location");
        String status = (String) req.get("room_status");
        // Regra 2.13 (ampliada na v2.9): pelo menos um campo precisa vir preenchido
        if (name.isEmpty() && capacidade.isEmpty() && location.isEmpty() && status.isEmpty()
                && recursos == null && slots == null) {
            nota("update_room RECUSADO (400): todos os campos vazios, nada para atualizar");
            return resposta(OP, "400", "Nenhum dado para atualizar");
        }

        synchronized (banco) {
            Map<String, Object> negado = exigirAdmin(OP, "update_room", (String) req.get("token"));
            if (negado != null) return negado;
            BancoDados.Sala s = banco.buscarSala(Long.parseLong((String) req.get("room_id")));
            if (s == null) {
                nota("update_room RECUSADO (404): sala " + req.get("room_id") + " nao existe");
                return resposta(OP, "404", "Sala nao encontrada");
            }
            // 409, nesta ordem: nome ja cadastrado -> capacidade abaixo de reserva ativa (secao 4, passo 9)
            if (!name.isEmpty() && banco.existeNomeSala(name, s.id)) {
                nota("update_room RECUSADO (409): ja existe outra sala chamada '" + name + "'");
                return resposta(OP, "409", "Sala ja cadastrada");
            }
            if (!capacidade.isEmpty()) {
                int nova = Integer.parseInt(capacidade);
                LocalDateTime agora = LocalDateTime.now(BancoDados.FUSO);
                for (BancoDados.Reserva r : banco.reservasDaSala(s.id)) {
                    if (r.ativa(agora) && r.participants > nova) {
                        nota("update_room RECUSADO (409): a reserva " + r.id + " (" + r.date + " " + r.start + ") tem "
                                + r.participants + " participantes, acima da nova capacidade " + nova);
                        return resposta(OP, "409", "Capacidade menor que participantes de reservas ativas");
                    }
                }
            }
            List<String> mudancas = new ArrayList<>();
            if (!name.isEmpty()) {
                mudancas.add("nome '" + s.name + "' -> '" + name + "'");
                s.name = name;
            }
            if (!capacidade.isEmpty()) {
                mudancas.add("capacidade " + s.capacity + " -> " + capacidade);
                s.capacity = Integer.parseInt(capacidade);
            }
            if (!location.isEmpty()) {
                mudancas.add("local '" + s.location + "' -> '" + location + "'");
                s.location = location;
            }
            if (recursos != null) {
                mudancas.add("recursos " + s.resources + " -> " + recursos);
                s.resources = recursos;
            }
            if (slots != null) {
                mudancas.add("horarios " + Aulas.resumo(s.slots) + " -> " + Aulas.resumo(slots));
                s.slots = slots;
            }
            if (!status.isEmpty()) {
                mudancas.add("status '" + s.status + "' -> '" + status + "'");
                s.status = status;
            }
            nota("update_room OK (200) na sala " + s.id + ": " + String.join(", ", mudancas));
        }
        return resposta(OP, "200", "Sala atualizada com sucesso");
    }

    private Map<String, Object> deleteRoom(Map<String, Object> req) {
        final String OP = "delete_room_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "room_id", Protocolo.ID, false, problemas);
        if (!problemas.isEmpty()) {
            nota("delete_room RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        synchronized (banco) {
            Map<String, Object> negado = exigirAdmin(OP, "delete_room", (String) req.get("token"));
            if (negado != null) return negado;
            BancoDados.Sala s = banco.buscarSala(Long.parseLong((String) req.get("room_id")));
            if (s == null) {
                nota("delete_room RECUSADO (404): sala " + req.get("room_id") + " nao existe");
                return resposta(OP, "404", "Sala nao encontrada");
            }
            LocalDateTime agora = LocalDateTime.now(BancoDados.FUSO);
            for (BancoDados.Reserva r : banco.reservasDaSala(s.id)) {
                if (r.ativa(agora)) {
                    nota("delete_room RECUSADO (409): a sala " + s.id + " tem reserva ativa (" + r.id + " em "
                            + r.date + " " + r.start + "). Para tira-la de uso, use room_status 'inactive'.");
                    return resposta(OP, "409", "Sala possui reservas ativas");
                }
            }
            int antigas = 0;
            for (BancoDados.Reserva r : banco.reservasDaSala(s.id)) {
                banco.removerReserva(r.id); // regra 9.8: so sobraram reservas ja encerradas
                antigas++;
            }
            banco.removerSala(s.id);
            nota("delete_room OK (200): sala " + s.id + " '" + s.name + "' removida"
                    + (antigas > 0 ? " junto com " + antigas + " reserva(s) ja encerrada(s)" : ""));
        }
        return resposta(OP, "200", "Sala removida com sucesso");
    }

    // =====================================================================
    // RESERVAS (o dono e identificado pelo token; horarios seguem a tabela de aulas, regra 8.3)
    // =====================================================================

    private Map<String, Object> checkAvailability(Map<String, Object> req) {
        final String OP = "check_availability_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "room_id", Protocolo.ID, false, problemas);
        checar(req, "date", Protocolo.DATA, false, problemas);
        checar(req, "start_time", Protocolo.HORA, false, problemas);
        checar(req, "end_time", Protocolo.HORA, false, problemas);
        Periodo periodo = null;
        if (problemas.isEmpty()) {
            periodo = lerPeriodo((String) req.get("date"), (String) req.get("start_time"), (String) req.get("end_time"), problemas);
        }
        if (!problemas.isEmpty()) {
            nota("check_availability RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        synchronized (banco) {
            BancoDados.Usuario autor = banco.autenticar((String) req.get("token"));
            if (autor == null) return nao401(OP, "check_availability");
            BancoDados.Sala s = banco.buscarSala(Long.parseLong((String) req.get("room_id")));
            if (s == null) {
                nota("check_availability RECUSADO (404): sala " + req.get("room_id") + " nao existe");
                return resposta(OP, "404", "Sala nao encontrada");
            }
            String motivo = motivoIndisponivel(s, periodo, -1);
            Map<String, Object> r = resposta(OP, "200", "Consulta realizada com sucesso");
            r.put("room_id", String.valueOf(s.id));
            r.put("date", req.get("date"));
            r.put("start_time", req.get("start_time"));
            r.put("end_time", req.get("end_time"));
            r.put("available", String.valueOf(motivo == null));
            nota("check_availability OK (200): sala " + s.id + " em " + periodo + " -> "
                    + (motivo == null ? "livre" : "indisponivel (" + motivo + ")"));
            return r;
        }
    }

    private Map<String, Object> createReservation(Map<String, Object> req) {
        final String OP = "create_reservation_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "room_id", Protocolo.ID, false, problemas);
        checar(req, "date", Protocolo.DATA, false, problemas);
        checar(req, "start_time", Protocolo.HORA, false, problemas);
        checar(req, "end_time", Protocolo.HORA, false, problemas);
        checar(req, "topic", Protocolo.ASSUNTO, false, problemas);
        checar(req, "participants", Protocolo.CAPACIDADE, false, problemas);
        Periodo periodo = null;
        if (problemas.isEmpty()) {
            periodo = lerPeriodo((String) req.get("date"), (String) req.get("start_time"), (String) req.get("end_time"), problemas);
        }
        if (periodo != null && LocalDateTime.of(periodo.data, periodo.inicio).isBefore(LocalDateTime.now(BancoDados.FUSO))) {
            problemas.add("inicio no passado (" + periodo + "), regra 9.3");
        }
        if (!problemas.isEmpty()) {
            nota("create_reservation RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados da reserva em formato invalido");
        }
        int participantes = Integer.parseInt((String) req.get("participants"));
        String topic = (String) req.get("topic");

        // Regra 5.1: verificar e gravar na MESMA secao critica
        synchronized (banco) {
            BancoDados.Usuario autor = banco.autenticar((String) req.get("token"));
            if (autor == null) return nao401(OP, "create_reservation");
            BancoDados.Sala s = banco.buscarSala(Long.parseLong((String) req.get("room_id")));
            if (s == null) {
                nota("create_reservation RECUSADO (404): sala " + req.get("room_id") + " nao existe");
                return resposta(OP, "404", "Sala nao encontrada");
            }
            if (participantes > s.capacity) {
                nota("create_reservation RECUSADO (400): " + participantes + " participantes, a sala " + s.id
                        + " tem " + s.capacity + " lugares");
                return resposta(OP, "400", "Dados da reserva em formato invalido");
            }
            Map<String, Object> conflito = conflitoDeReserva(OP, "create_reservation", s, periodo, -1, true);
            if (conflito != null) return conflito;
            BancoDados.Reserva nova = banco.criarReserva(s.id, autor.email, periodo.data, periodo.inicio, periodo.fim,
                    topic, participantes);
            nota("create_reservation OK (201): reserva " + nova.id + " de '" + autor.user + "' na sala " + s.id
                    + " '" + s.name + "' em " + periodo + " (" + aulasTexto(periodo) + "), " + participantes + " participante(s)");
            Map<String, Object> r = resposta(OP, "201", "Reserva realizada com sucesso");
            r.put("reservation_id", String.valueOf(nova.id));
            return r;
        }
    }

    private Map<String, Object> listReservations(Map<String, Object> req) {
        final String OP = "list_reservations_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checarOpcional(req, "scope", Protocolo.SCOPE, problemas);
        checarOpcional(req, "date", Protocolo.DATA, problemas);
        checarOpcional(req, "room_id", Protocolo.ID, problemas);
        LocalDate data = null;
        if (problemas.isEmpty() && req.containsKey("date")) {
            data = lerData((String) req.get("date"), problemas);
        }
        if (!problemas.isEmpty()) {
            nota("list_reservations RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Filtros em formato invalido");
        }
        String scope = req.containsKey("scope") ? (String) req.get("scope") : "mine";
        Long sala = req.containsKey("room_id") ? Long.valueOf((String) req.get("room_id")) : null;

        synchronized (banco) {
            String token = (String) req.get("token");
            BancoDados.Usuario autor = banco.autenticar(token);
            if (autor == null) return nao401(OP, "list_reservations");
            if ("all".equals(scope) && !banco.tokenEhAdmin(token)) {
                nota("list_reservations RECUSADO (403): scope 'all' exige admin; o token de '" + autor.user
                        + "' nao esta na whitelist");
                return resposta(OP, "403", "Permissao insuficiente");
            }
            List<Object> lista = new ArrayList<>();
            for (BancoDados.Reserva r : banco.listarReservas()) {
                if ("mine".equals(scope) && !r.ownerEmail.equals(autor.email)) continue;
                if (data != null && !r.date.equals(data)) continue;
                if (sala != null && r.roomId != sala) continue;
                lista.add(dadosReserva(r));
            }
            Map<String, Object> resp = resposta(OP, "200", "Consulta realizada com sucesso");
            resp.put("count", String.valueOf(lista.size()));
            resp.put("reservations", lista);
            nota("list_reservations OK (200): " + lista.size() + " reserva(s), scope '" + scope + "'"
                    + (data != null ? ", data " + data : "") + (sala != null ? ", sala " + sala : ""));
            return resp;
        }
    }

    private Map<String, Object> readReservation(Map<String, Object> req) {
        final String OP = "read_reservation_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "reservation_id", Protocolo.ID, false, problemas);
        if (!problemas.isEmpty()) {
            nota("read_reservation RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        synchronized (banco) {
            String token = (String) req.get("token");
            BancoDados.Usuario autor = banco.autenticar(token);
            if (autor == null) return nao401(OP, "read_reservation");
            BancoDados.Reserva r = banco.buscarReserva(Long.parseLong((String) req.get("reservation_id")));
            if (r == null) {
                nota("read_reservation RECUSADO (404): reserva " + req.get("reservation_id") + " nao existe");
                return resposta(OP, "404", "Reserva nao encontrada");
            }
            if (!r.ownerEmail.equals(autor.email) && !banco.tokenEhAdmin(token)) {
                nota("read_reservation RECUSADO (403): a reserva " + r.id + " e de '" + banco.nomeDoDono(r)
                        + "', nao de '" + autor.user + "'");
                return resposta(OP, "403", "Permissao insuficiente");
            }
            Map<String, Object> resp = resposta(OP, "200", "Consulta realizada com sucesso");
            resp.putAll(dadosReserva(r));
            resp.put("created_at", r.createdAt);
            nota("read_reservation OK (200): reserva " + r.id + " enviada");
            return resp;
        }
    }

    /**
     * So o dono altera, inclusive para o admin (regra 9.9). room_id e date nao mudam (regra 9.10).
     * "" = nao alterar; tudo vazio -> "Nenhum dado para atualizar" (regra 2.13).
     */
    private Map<String, Object> updateReservation(Map<String, Object> req) {
        final String OP = "update_reservation_response";
        List<String> problemas = new ArrayList<>();
        if (req.containsKey("room_id")) problemas.add("a chave 'room_id' nao pode ser enviada (regra 9.10)");
        if (req.containsKey("date")) problemas.add("a chave 'date' nao pode ser enviada (regra 9.10)");
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "reservation_id", Protocolo.ID, false, problemas);
        checar(req, "start_time", Protocolo.HORA, true, problemas);
        checar(req, "end_time", Protocolo.HORA, true, problemas);
        checar(req, "topic", Protocolo.ASSUNTO, true, problemas);
        checar(req, "participants", Protocolo.CAPACIDADE, true, problemas);
        if (problemas.isEmpty()) {
            String i = (String) req.get("start_time");
            String f = (String) req.get("end_time");
            if (!i.isEmpty() && !Aulas.ehInicio(i)) problemas.add("start_time " + i + " nao e inicio de aula (regra 8.3)");
            if (!f.isEmpty() && !Aulas.ehFim(f)) problemas.add("end_time " + f + " nao e fim de aula (regra 8.3)");
        }
        if (!problemas.isEmpty()) {
            nota("update_reservation RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados da reserva em formato invalido");
        }
        String ini = (String) req.get("start_time");
        String fim = (String) req.get("end_time");
        String topic = (String) req.get("topic");
        String part = (String) req.get("participants");
        if (ini.isEmpty() && fim.isEmpty() && topic.isEmpty() && part.isEmpty()) {
            nota("update_reservation RECUSADO (400): todos os campos vazios, nada para atualizar");
            return resposta(OP, "400", "Nenhum dado para atualizar");
        }

        // Regra 5.1: mudar o horario passa pela mesma secao critica do create_reservation
        synchronized (banco) {
            BancoDados.Usuario autor = banco.autenticar((String) req.get("token"));
            if (autor == null) return nao401(OP, "update_reservation");
            BancoDados.Reserva r = banco.buscarReserva(Long.parseLong((String) req.get("reservation_id")));
            if (r == null) {
                nota("update_reservation RECUSADO (404): reserva " + req.get("reservation_id") + " nao existe");
                return resposta(OP, "404", "Reserva nao encontrada");
            }
            if (!r.ownerEmail.equals(autor.email)) {
                nota("update_reservation RECUSADO (403): a reserva " + r.id + " e de '" + banco.nomeDoDono(r)
                        + "'; so o dono altera, inclusive para o admin (regra 9.9)");
                return resposta(OP, "403", "Permissao insuficiente");
            }
            LocalDateTime agora = LocalDateTime.now(BancoDados.FUSO);
            if (!r.ativa(agora)) {
                nota("update_reservation RECUSADO (409): a reserva " + r.id + " ja terminou");
                return resposta(OP, "409", "Reserva ja encerrada");
            }
            LocalTime novoIni = ini.isEmpty() ? r.start : LocalTime.parse(ini);
            LocalTime novoFim = fim.isEmpty() ? r.end : LocalTime.parse(fim);
            int novosPart = part.isEmpty() ? r.participants : Integer.parseInt(part);
            BancoDados.Sala s = banco.buscarSala(r.roomId);
            // 400 que depende do registro (secao 4, passo 8)
            if (!novoFim.isAfter(novoIni)) {
                nota("update_reservation RECUSADO (400): fim " + novoFim + " nao e depois do inicio " + novoIni);
                return resposta(OP, "400", "Dados da reserva em formato invalido");
            }
            if (!ini.isEmpty() && LocalDateTime.of(r.date, novoIni).isBefore(agora)) {
                nota("update_reservation RECUSADO (400): novo inicio " + r.date + " " + novoIni + " no passado (regra 9.3)");
                return resposta(OP, "400", "Dados da reserva em formato invalido");
            }
            if (s != null && novosPart > s.capacity) {
                nota("update_reservation RECUSADO (400): " + novosPart + " participantes, a sala tem " + s.capacity + " lugares");
                return resposta(OP, "400", "Dados da reserva em formato invalido");
            }
            boolean mudaHorario = !ini.isEmpty() || !fim.isEmpty();
            if (mudaHorario && s != null) {
                Periodo novo = new Periodo(r.date, novoIni, novoFim);
                Map<String, Object> conflito = conflitoDeReserva(OP, "update_reservation", s, novo, r.id, true);
                if (conflito != null) return conflito;
            }
            List<String> mudancas = new ArrayList<>();
            if (mudaHorario) mudancas.add("horario " + r.start + "-" + r.end + " -> " + novoIni + "-" + novoFim);
            if (!topic.isEmpty()) mudancas.add("assunto '" + r.topic + "' -> '" + topic + "'");
            if (!part.isEmpty()) mudancas.add("participantes " + r.participants + " -> " + novosPart);
            r.start = novoIni;
            r.end = novoFim;
            if (!topic.isEmpty()) r.topic = topic;
            r.participants = novosPart;
            nota("update_reservation OK (200) na reserva " + r.id + ": " + String.join(", ", mudancas));
        }
        return resposta(OP, "200", "Reserva atualizada com sucesso");
    }

    private Map<String, Object> deleteReservation(Map<String, Object> req) {
        final String OP = "delete_reservation_response";
        List<String> problemas = new ArrayList<>();
        checar(req, "token", Protocolo.TOKEN, false, problemas);
        checar(req, "reservation_id", Protocolo.ID, false, problemas);
        if (!problemas.isEmpty()) {
            nota("delete_reservation RECUSADO (400): " + problemas);
            return resposta(OP, "400", "Dados em formato invalido");
        }
        synchronized (banco) {
            String token = (String) req.get("token");
            BancoDados.Usuario autor = banco.autenticar(token);
            if (autor == null) return nao401(OP, "delete_reservation");
            BancoDados.Reserva r = banco.buscarReserva(Long.parseLong((String) req.get("reservation_id")));
            if (r == null) {
                nota("delete_reservation RECUSADO (404): reserva " + req.get("reservation_id") + " nao existe");
                return resposta(OP, "404", "Reserva nao encontrada");
            }
            if (!r.ownerEmail.equals(autor.email) && !banco.tokenEhAdmin(token)) {
                nota("delete_reservation RECUSADO (403): a reserva " + r.id + " e de '" + banco.nomeDoDono(r) + "'");
                return resposta(OP, "403", "Permissao insuficiente");
            }
            if (!r.ativa(LocalDateTime.now(BancoDados.FUSO))) {
                nota("delete_reservation RECUSADO (409): a reserva " + r.id + " ja terminou");
                return resposta(OP, "409", "Reserva ja encerrada");
            }
            banco.removerReserva(r.id);
            nota("delete_reservation OK (200): reserva " + r.id + " cancelada, sala " + r.roomId + " liberada em "
                    + r.date + " " + r.start + "-" + r.end);
        }
        return resposta(OP, "200", "Reserva cancelada com sucesso");
    }

    // ---------------------------------------------------------------- auxiliares de salas e reservas

    /** Data + horario de inicio e fim ja validados. */
    private static final class Periodo {
        final LocalDate data;
        final LocalTime inicio;
        final LocalTime fim;

        Periodo(LocalDate data, LocalTime inicio, LocalTime fim) {
            this.data = data;
            this.inicio = inicio;
            this.fim = fim;
        }

        @Override
        public String toString() {
            return data + " " + inicio + "-" + fim;
        }
    }

    /**
     * Converte data e horarios que ja passaram na regex. Viram problema (400): data inexistente (regra 9.2),
     * horario fora da tabela de aulas (regra 8.3) e fim antes ou igual ao inicio (regra 9.11).
     */
    private static Periodo lerPeriodo(String data, String inicio, String fim, List<String> problemas) {
        LocalDate d = lerData(data, problemas);
        boolean ok = d != null;
        if (!Aulas.ehInicio(inicio)) {
            problemas.add("start_time " + inicio + " nao e inicio de aula da tabela (regra 8.3)");
            ok = false;
        }
        if (!Aulas.ehFim(fim)) {
            problemas.add("end_time " + fim + " nao e fim de aula da tabela (regra 8.3)");
            ok = false;
        }
        if (!ok) return null;
        LocalTime i = LocalTime.parse(inicio);
        LocalTime f = LocalTime.parse(fim);
        if (!f.isAfter(i)) {
            problemas.add("end_time (" + fim + ") precisa ser depois de start_time (" + inicio + ")");
            return null;
        }
        return new Periodo(d, i, f);
    }

    private static LocalDate lerData(String data, List<String> problemas) {
        try {
            return LocalDate.parse(data); // ISO estrito: 2026-02-30 e recusado (regra 9.2)
        } catch (DateTimeParseException e) {
            problemas.add("'date' nao e uma data que existe (" + data + ")");
            return null;
        }
    }

    /** null se a sala esta livre no periodo; senao o motivo (para o log). */
    private String motivoIndisponivel(BancoDados.Sala s, Periodo p, long excetoReserva) {
        if (!"active".equals(s.status)) return "sala inactive";
        if (!Aulas.dentroDoFuncionamento(s.slots, p.data, p.inicio, p.fim)) return "fora do funcionamento da sala";
        BancoDados.Reserva outra = banco.conflito(s.id, p.data, p.inicio, p.fim, excetoReserva);
        if (outra != null) return "ja reservada (reserva " + outra.id + ")";
        return null;
    }

    /**
     * Os 409 da reserva, nesta ordem (regra 8.4 e secao 4, passo 9):
     * sala inactive -> fora do funcionamento -> periodo ocupado. null = sem conflito.
     */
    private Map<String, Object> conflitoDeReserva(String opResposta, String op, BancoDados.Sala s, Periodo p,
                                                  long excetoReserva, boolean checarInativa) {
        if (checarInativa && !"active".equals(s.status)) {
            nota(op + " RECUSADO (409): sala " + s.id + " esta 'inactive'");
            return resposta(opResposta, "409", "Sala indisponivel para reserva");
        }
        if (!Aulas.dentroDoFuncionamento(s.slots, p.data, p.inicio, p.fim)) {
            nota(op + " RECUSADO (409): " + p + " (" + aulasTexto(p) + ") fora dos horarios da sala "
                    + s.id + ": " + Aulas.resumo(s.slots));
            return resposta(opResposta, "409", "Horario fora do funcionamento da sala");
        }
        BancoDados.Reserva outra = banco.conflito(s.id, p.data, p.inicio, p.fim, excetoReserva);
        if (outra != null) {
            nota(op + " RECUSADO (409): " + p + " sobrepoe a reserva " + outra.id + " (" + outra.start + "-"
                    + outra.end + " de '" + banco.nomeDoDono(outra) + "')");
            return resposta(opResposta, "409", "Sala ja reservada no periodo solicitado");
        }
        return null;
    }

    /** Codigos das aulas ocupadas pelo periodo, para o log. Ex.: "3T1, 3T2". */
    private static String aulasTexto(Periodo p) {
        String dia = Aulas.codigoDoDia(p.data);
        List<String> l = new ArrayList<>();
        for (Aulas.Aula a : Aulas.aulasEntre(p.inicio, p.fim)) l.add((dia == null ? "dom" : dia) + a.codigo);
        return String.join(", ", l);
    }

    /**
     * Regra 8.5: um item por horario da sala no dia da semana da data, na ordem da tabela.
     * Indisponivel = reservado ou sala inactive. Reservado traz 'user'; 'reservation_id' so para admin e dono.
     */
    private List<Object> montarAgenda(BancoDados.Sala s, LocalDate data, BancoDados.Usuario autor, boolean admin) {
        List<Object> agenda = new ArrayList<>();
        String dia = Aulas.codigoDoDia(data);
        if (dia == null) return agenda; // domingo: a sala nunca funciona
        List<BancoDados.Reserva> doDia = new ArrayList<>();
        for (BancoDados.Reserva r : banco.reservasDaSala(s.id)) {
            if (r.date.equals(data)) doDia.add(r);
        }
        for (Aulas.Aula a : Aulas.TABELA) {
            String codigo = dia + a.codigo;
            if (!s.slots.contains(codigo)) continue;
            BancoDados.Reserva dona = null;
            for (BancoDados.Reserva r : doDia) {
                if (r.start.isBefore(a.fim) && r.end.isAfter(a.inicio)) {
                    dona = r;
                    break;
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slot", codigo);
            item.put("start_time", a.inicioTexto());
            item.put("end_time", a.fimTexto());
            item.put("slot_status", dona != null || !"active".equals(s.status) ? "indisponivel" : "livre");
            if (dona != null) {
                item.put("user", banco.nomeDoDono(dona));
                if (admin || dona.ownerEmail.equals(autor.email)) {
                    item.put("reservation_id", String.valueOf(dona.id));
                }
            }
            agenda.add(item);
        }
        return agenda;
    }

    private static Map<String, Object> dadosSala(BancoDados.Sala s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("room_id", String.valueOf(s.id));
        m.put("name", s.name);
        m.put("capacity", String.valueOf(s.capacity));
        m.put("location", s.location);
        m.put("resources", new ArrayList<Object>(s.resources));
        m.put("room_status", s.status);
        m.put("slots", new ArrayList<Object>(s.slots));
        return m;
    }

    private Map<String, Object> dadosReserva(BancoDados.Reserva r) {
        BancoDados.Sala s = banco.buscarSala(r.roomId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reservation_id", String.valueOf(r.id));
        m.put("room_id", String.valueOf(r.roomId));
        m.put("name", s == null ? "" : s.name);
        m.put("user", banco.nomeDoDono(r));
        m.put("date", r.date.toString());
        m.put("start_time", r.start.toString());
        m.put("end_time", r.end.toString());
        m.put("topic", r.topic);
        m.put("participants", String.valueOf(r.participants));
        return m;
    }

    /**
     * 'resources': array so de strings, cada uma no formato ^[A-Za-z0-9 -]{1,30}$ e diferente de "0" (regra 9.7).
     * Na criacao e obrigatorio ([] = sala sem recursos).
     * Na alteracao (atualizacao=true): ausente ou [] devolvem null (= manter); ["0"] devolve lista vazia (= apagar todos).
     */
    private static List<String> checarRecursos(Map<String, Object> req, boolean obrigatorio, boolean atualizacao,
                                               List<String> problemas) {
        if (!req.containsKey("resources")) {
            if (obrigatorio) problemas.add("'resources' ausente");
            return atualizacao ? null : new ArrayList<>();
        }
        Object v = req.get("resources");
        if (!(v instanceof List)) {
            problemas.add("'resources' precisa ser um array de strings");
            return null;
        }
        List<?> itens = (List<?>) v;
        if (atualizacao && itens.isEmpty()) return null;
        if (atualizacao && itens.size() == 1 && "0".equals(itens.get(0))) return new ArrayList<>();
        List<String> lista = new ArrayList<>();
        for (Object item : itens) {
            if (!(item instanceof String)) {
                problemas.add("'resources' tem item que nao e string");
                return null;
            }
            String r = (String) item;
            if ("0".equals(r)) {
                problemas.add("recurso \"0\" e reservado (so [\"0\"], sozinho, no update_room, apaga todos)");
                return null;
            }
            if (!Protocolo.RECURSO.matcher(r).matches()) {
                problemas.add("recurso '" + r + "' fora do formato " + Protocolo.RECURSO.pattern());
                return null;
            }
            lista.add(r);
        }
        return lista;
    }

    /**
     * 'slots': array de codigos da UTFPR (2M1), sem repetidos (regras 8.1 e 8.2).
     * Na criacao e obrigatorio e precisa ter pelo menos um. Na alteracao, ausente ou [] devolvem null (= manter).
     */
    private static List<String> checarSlots(Map<String, Object> req, boolean obrigatorio, List<String> problemas) {
        if (!req.containsKey("slots")) {
            if (obrigatorio) problemas.add("'slots' ausente");
            return null;
        }
        Object v = req.get("slots");
        if (!(v instanceof List)) {
            problemas.add("'slots' precisa ser um array de strings");
            return null;
        }
        List<?> itens = (List<?>) v;
        if (itens.isEmpty()) {
            if (obrigatorio) problemas.add("'slots' precisa ter pelo menos um horario");
            return null;
        }
        List<String> lista = new ArrayList<>();
        for (Object item : itens) {
            if (!(item instanceof String) || !Protocolo.SLOT.matcher((String) item).matches()) {
                problemas.add("horario " + item + " fora do formato " + Protocolo.SLOT.pattern() + " (ex.: 2M1)");
                return null;
            }
            if (lista.contains(item)) {
                problemas.add("horario " + item + " repetido em 'slots'");
                return null;
            }
            lista.add((String) item);
        }
        lista.sort(Comparator.comparingInt(Aulas::ordem));
        return lista;
    }

    /** Campo opcional: se vier, nao pode ser "" nem null e precisa casar com a regex (regra 2.14). */
    private static void checarOpcional(Map<String, Object> req, String campo, Pattern regex, List<String> problemas) {
        if (req.containsKey(campo)) {
            checar(req, campo, regex, false, problemas);
        }
    }

    private Map<String, Object> nao401(String opResposta, String op) {
        nota(op + " RECUSADO (401): token inexistente, invalidado ou expirado");
        return resposta(opResposta, "401", "Token invalido ou expirado");
    }

    // =====================================================================
    // AUXILIARES
    // =====================================================================

    /**
     * Confere um campo obrigatorio: precisa existir, nao pode ser null, precisa ser string
     * e casar com a regex. Se vazioPermitido, a string "" tambem e aceita (update = nao alterar).
     */
    private static void checar(Map<String, Object> req, String campo, Pattern regex,
                               boolean vazioPermitido, List<String> problemas) {
        if (!req.containsKey(campo)) {
            problemas.add("'" + campo + "' ausente");
            return;
        }
        Object valor = req.get(campo);
        if (valor == null) {
            problemas.add("'" + campo + "' e null");
            return;
        }
        if (!(valor instanceof String)) {
            problemas.add("'" + campo + "' nao e string");
            return;
        }
        String s = (String) valor;
        if (s.isEmpty() && vazioPermitido) {
            return;
        }
        if (!regex.matcher(s).matches()) {
            problemas.add("'" + campo + "' fora do formato " + regex.pattern());
        } else if (regex == Protocolo.EMAIL && s.length() > Protocolo.EMAIL_MAXIMO) {
            problemas.add("'" + campo + "' com mais de " + Protocolo.EMAIL_MAXIMO + " caracteres");
        }
    }

    static Map<String, Object> resposta(String op, String status, String message) {
        Map<String, Object> r = new LinkedHashMap<>(); // mantem 'op' como primeira chave
        r.put("op", op);
        r.put("status", status);
        r.put("message", message);
        return r;
    }

    static Map<String, Object> erroProtocolo(String message) {
        return resposta("error", "400", message);
    }

    private void nota(String texto) {
        Log.print(origem, "   * " + texto);
    }
}
