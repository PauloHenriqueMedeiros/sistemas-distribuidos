package servidor;

import comum.Json;
import comum.Log;
import comum.Protocolo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recebe o texto de uma requisicao e devolve o objeto de resposta,
 * seguindo o protocolo v2.4 da turma.
 *
 * Ordem de validacao usada (sugerida no protocolo; obrigatoria no login desde a v2.2):
 *   JSON -> op -> campos/regex (400) -> token (401) -> permissao (403) -> conflito (409) -> executa
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
            nota("login OK (200): '" + u.user + "' (role " + u.role + ") recebeu token " + token);
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
            banco.removerUsuario(u.email);
            nota("delete_user OK (200): usuario '" + u.user + "' <" + u.email + "> removido, token invalidado; "
                    + "user e email livres para novo cadastro");
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
                mudancas.add("role '" + u.role + "' -> '" + novoRole + "'");
                u.role = novoRole;
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
            banco.removerUsuario(u.email);
            nota("admin_delete_user OK (200): usuario '" + u.user + "' <" + u.email + "> removido e sessao encerrada; "
                    + "user e email livres para novo cadastro");
        }
        return resposta(OP, "200", "Usuario removido com sucesso");
    }

    /**
     * Confere o token (401) e o role admin (403). Devolve a resposta de erro,
     * ou null se o autor e admin. Chamar dentro de synchronized (banco).
     */
    private Map<String, Object> exigirAdmin(String opResposta, String op, String token) {
        BancoDados.Usuario autor = banco.autenticar(token);
        if (autor == null) {
            nota(op + " RECUSADO (401): token inexistente, invalidado ou expirado");
            return resposta(opResposta, "401", "Token invalido ou expirado");
        }
        if (!"admin".equals(autor.role)) {
            nota(op + " RECUSADO (403): '" + autor.user + "' tem role 'user', operacao exige 'admin'");
            return resposta(opResposta, "403", "Permissao insuficiente");
        }
        return null;
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
