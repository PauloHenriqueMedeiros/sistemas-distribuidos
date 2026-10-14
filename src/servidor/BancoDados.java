package servidor;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Banco" do servidor, guardado EM MEMORIA (nada e gravado em disco):
 *
 *   usuariosPorEmail  : LinkedHashMap  email -> Usuario   (o "cadastro")
 *   sessoesPorToken   : HashMap        token -> Sessao    (tokens ativos, regra 3.2)
 *   tokensJaEmitidos  : HashSet        tokens ja gerados  (para nunca reutilizar, regra 3.4)
 *
 * Os dados existem enquanto o servidor estiver aberto; ao fechar, somem.
 * Quem consulta os cadastros e o admin, pelo cliente (admin_list_users / admin_read_user).
 *
 * Todos os metodos sao synchronized; operacoes compostas (ex.: verificar e depois gravar)
 * devem ser feitas dentro de synchronized (banco) { ... } para evitar condicao de corrida
 * entre as threads dos clientes (regra 3.11).
 */
public final class BancoDados {

    /** Regra 3.3: token vale 30 minutos sem uso; cada requisicao valida renova. */
    public static final long VALIDADE_TOKEN_MS = 30L * 60L * 1000L;

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    public static final class Usuario {
        public final String email; // imutavel (RNF 5.c) -> usado como chave
        public String user;
        public String password;
        public String role;
        public final String createdAt;

        Usuario(String email, String user, String password, String role, String createdAt) {
            this.email = email;
            this.user = user;
            this.password = password;
            this.role = role;
            this.createdAt = createdAt;
        }
    }

    private static final class Sessao {
        final String token;
        final String email;
        long ultimoUso;

        Sessao(String token, String email) {
            this.token = token;
            this.email = email;
            this.ultimoUso = System.currentTimeMillis();
        }

        boolean expirada(long agora) {
            return agora - ultimoUso > VALIDADE_TOKEN_MS;
        }
    }

    private final Map<String, Usuario> usuariosPorEmail = new LinkedHashMap<>();
    private final Map<String, Sessao> sessoesPorToken = new HashMap<>();
    private final Set<String> tokensJaEmitidos = new HashSet<>(); // regra 3.4: nunca reutilizar
    private final SecureRandom aleatorio = new SecureRandom();

    // ------------------------------------------------------------------ usuarios

    public synchronized Usuario buscarPorEmail(String email) {
        return usuariosPorEmail.get(email);
    }

    /** Busca pelo nome de usuario (target_user das operacoes admin_*). */
    public synchronized Usuario buscarPorUser(String user) {
        for (Usuario u : usuariosPorEmail.values()) {
            if (u.user.equals(user)) return u;
        }
        return null;
    }

    /** Copia da lista de cadastros, na ordem em que foram feitos (admin_list_users). */
    public synchronized List<Usuario> listarUsuarios() {
        return new ArrayList<>(usuariosPorEmail.values());
    }

    public synchronized boolean existeEmail(String email) {
        return usuariosPorEmail.containsKey(email);
    }

    public synchronized boolean existeUser(String user) {
        for (Usuario u : usuariosPorEmail.values()) {
            if (u.user.equals(user)) return true;
        }
        return false;
    }

    public synchronized Usuario criarUsuario(String email, String user, String password, String role) {
        Usuario u = new Usuario(email, user, password, role, LocalDateTime.now(FUSO).format(FORMATO_DATA));
        usuariosPorEmail.put(email, u);
        return u;
    }

    public synchronized int contarAdmins() {
        int total = 0;
        for (Usuario u : usuariosPorEmail.values()) {
            if ("admin".equals(u.role)) total++;
        }
        return total;
    }

    /**
     * Regra 3.13: remover a conta apaga tudo dela. Apaga o cadastro e encerra todas as sessoes;
     * o user e o email ficam livres para um novo register.
     * (No EP-1 ainda nao existem reservas; quando existirem, tambem serao apagadas aqui.)
     */
    public synchronized void removerUsuario(String email) {
        usuariosPorEmail.remove(email);
        sessoesPorToken.values().removeIf(s -> s.email.equals(email));
    }

    // ------------------------------------------------------------------ sessoes / token

    /** Regra 3.5: sessao unica por usuario. */
    public synchronized boolean temSessaoAtiva(String email) {
        long agora = System.currentTimeMillis();
        Iterator<Sessao> it = sessoesPorToken.values().iterator();
        boolean ativa = false;
        while (it.hasNext()) {
            Sessao s = it.next();
            if (s.expirada(agora)) {
                it.remove(); // limpeza de sessoes vencidas
            } else if (s.email.equals(email)) {
                ativa = true;
            }
        }
        return ativa;
    }

    /** Regra 3.1: 64 caracteres hexadecimais minusculos. */
    public synchronized String criarSessao(String email) {
        String token;
        do {
            byte[] bytes = new byte[32];
            aleatorio.nextBytes(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            token = sb.toString();
        } while (tokensJaEmitidos.contains(token));
        tokensJaEmitidos.add(token);
        sessoesPorToken.put(token, new Sessao(token, email));
        return token;
    }

    /**
     * Devolve o dono do token, ou null se o token nao existe, foi invalidado ou expirou.
     * Se for valido, renova a contagem de 30 minutos.
     */
    public synchronized Usuario autenticar(String token) {
        Sessao s = sessoesPorToken.get(token);
        if (s == null) return null;
        long agora = System.currentTimeMillis();
        if (s.expirada(agora)) {
            sessoesPorToken.remove(token);
            return null;
        }
        Usuario u = usuariosPorEmail.get(s.email);
        if (u == null) {
            sessoesPorToken.remove(token);
            return null;
        }
        s.ultimoUso = agora;
        return u;
    }

    public synchronized void encerrarSessao(String token) {
        sessoesPorToken.remove(token);
    }
}
