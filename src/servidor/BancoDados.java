package servidor;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
 *   usuariosPorEmail  : LinkedHashMap  email -> Usuario      (o cadastro)
 *   sessoesPorToken   : HashMap        token -> Sessao       (tokens ativos, regra 3.2)
 *   tokensAdmin       : HashSet        tokens com poder de admin (whitelist, regra 3.14)
 *   tokensJaEmitidos  : HashSet        tokens ja gerados     (para nunca reutilizar, regra 3.4)
 *   salasPorId        : LinkedHashMap  room_id -> Sala
 *   reservasPorId     : LinkedHashMap  reservation_id -> Reserva
 *
 * Os dados existem enquanto o servidor estiver aberto; ao fechar, somem.
 *
 * Todos os metodos sao synchronized. Operacoes compostas (verificar e depois gravar) sao feitas
 * pelo Operacoes dentro de synchronized (banco) { ... }: assim a verificacao de conflito e a gravacao
 * de uma reserva ficam na MESMA secao critica (regra 5.1), e o mesmo vale para login (regra 3.11).
 */
public final class BancoDados {

    /** Regra 3.3: token vale 30 minutos sem uso; cada requisicao valida renova. */
    public static final long VALIDADE_TOKEN_MS = 30L * 60L * 1000L;

    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String agoraTexto() {
        return LocalDateTime.now(FUSO).format(FORMATO_DATA);
    }

    // ================================================================== registros

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

    public static final class Sala {
        public final long id;
        public String name;
        public int capacity;
        public String location;
        public List<String> resources;
        public String status; // "active" ou "inactive"

        Sala(long id, String name, int capacity, String location, List<String> resources) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
            this.location = location;
            this.resources = new ArrayList<>(resources);
            this.status = "active";
        }
    }

    public static final class Reserva {
        public final long id;
        public final long roomId;
        public final String ownerEmail; // dono pelo email (imutavel); o nome atual vem do cadastro
        public final LocalDate date;
        public LocalTime start;
        public LocalTime end;
        public String topic;
        public int participants;
        public final String createdAt;

        Reserva(long id, long roomId, String ownerEmail, LocalDate date, LocalTime start, LocalTime end,
                String topic, int participants, String createdAt) {
            this.id = id;
            this.roomId = roomId;
            this.ownerEmail = ownerEmail;
            this.date = date;
            this.start = start;
            this.end = end;
            this.topic = topic;
            this.participants = participants;
            this.createdAt = createdAt;
        }

        /** Ainda nao terminou (fim depois de agora). */
        public boolean ativa(LocalDateTime agora) {
            return LocalDateTime.of(date, end).isAfter(agora);
        }
    }

    private final Map<String, Usuario> usuariosPorEmail = new LinkedHashMap<>();
    private final Map<String, Sessao> sessoesPorToken = new HashMap<>();
    private final Set<String> tokensAdmin = new HashSet<>();      // regra 3.14
    private final Set<String> tokensJaEmitidos = new HashSet<>(); // regra 3.4
    private final Map<Long, Sala> salasPorId = new LinkedHashMap<>();
    private final Map<Long, Reserva> reservasPorId = new LinkedHashMap<>();
    private long proximoIdSala = 1;
    private long proximoIdReserva = 1;
    private final SecureRandom aleatorio = new SecureRandom();

    // ================================================================== usuarios

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

    public synchronized List<Usuario> listarUsuarios() {
        return new ArrayList<>(usuariosPorEmail.values());
    }

    public synchronized boolean existeEmail(String email) {
        return usuariosPorEmail.containsKey(email);
    }

    public synchronized boolean existeUser(String user) {
        return buscarPorUser(user) != null;
    }

    public synchronized Usuario criarUsuario(String email, String user, String password, String role) {
        Usuario u = new Usuario(email, user, password, role, agoraTexto());
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
     * Regra 3.14: trocar o role atualiza a whitelist na hora.
     * Promover coloca os tokens ativos do usuario na lista; rebaixar tira.
     */
    public synchronized void alterarRole(Usuario u, String novoRole) {
        u.role = novoRole;
        for (Sessao s : sessoesPorToken.values()) {
            if (!s.email.equals(u.email)) continue;
            if ("admin".equals(novoRole)) {
                tokensAdmin.add(s.token);
            } else {
                tokensAdmin.remove(s.token);
            }
        }
    }

    /**
     * Regra 3.13: remover a conta apaga tudo dela: cadastro, sessoes (e o lugar na whitelist)
     * e TODAS as reservas, passadas e futuras. Devolve quantas reservas foram apagadas.
     */
    public synchronized int removerUsuario(String email) {
        usuariosPorEmail.remove(email);
        Iterator<Sessao> it = sessoesPorToken.values().iterator();
        while (it.hasNext()) {
            Sessao s = it.next();
            if (s.email.equals(email)) {
                tokensAdmin.remove(s.token);
                it.remove();
            }
        }
        int antes = reservasPorId.size();
        reservasPorId.values().removeIf(r -> r.ownerEmail.equals(email));
        return antes - reservasPorId.size();
    }

    // ================================================================== sessoes / token

    /** Regra 3.5: sessao unica por usuario. */
    public synchronized boolean temSessaoAtiva(String email) {
        limparSessoesVencidas();
        for (Sessao s : sessoesPorToken.values()) {
            if (s.email.equals(email)) return true;
        }
        return false;
    }

    /** Regra 3.1: 64 hex minusculos. Se a conta for admin, o token entra na whitelist (regra 3.14). */
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
        Usuario u = usuariosPorEmail.get(email);
        if (u != null && "admin".equals(u.role)) {
            tokensAdmin.add(token);
        }
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
        Usuario u = usuariosPorEmail.get(s.email);
        if (s.expirada(agora) || u == null) {
            sessoesPorToken.remove(token);
            tokensAdmin.remove(token);
            return null;
        }
        s.ultimoUso = agora;
        return u;
    }

    /** Regra 3.14: so a whitelist libera as operacoes de admin. */
    public synchronized boolean tokenEhAdmin(String token) {
        return tokensAdmin.contains(token);
    }

    public synchronized void encerrarSessao(String token) {
        sessoesPorToken.remove(token);
        tokensAdmin.remove(token);
    }

    private void limparSessoesVencidas() {
        long agora = System.currentTimeMillis();
        Iterator<Sessao> it = sessoesPorToken.values().iterator();
        while (it.hasNext()) {
            Sessao s = it.next();
            if (s.expirada(agora)) {
                tokensAdmin.remove(s.token);
                it.remove();
            }
        }
    }

    // ================================================================== salas

    public synchronized Sala criarSala(String name, int capacity, String location, List<String> resources) {
        Sala s = new Sala(proximoIdSala++, name, capacity, location, resources);
        salasPorId.put(s.id, s);
        return s;
    }

    public synchronized Sala buscarSala(long id) {
        return salasPorId.get(id);
    }

    public synchronized List<Sala> listarSalas() {
        return new ArrayList<>(salasPorId.values());
    }

    /** Nome ja usado por outra sala (excetoId = sala que esta sendo alterada, ou -1). */
    public synchronized boolean existeNomeSala(String name, long excetoId) {
        for (Sala s : salasPorId.values()) {
            if (s.id != excetoId && s.name.equals(name)) return true;
        }
        return false;
    }

    public synchronized void removerSala(long id) {
        salasPorId.remove(id);
    }

    // ================================================================== reservas

    public synchronized Reserva criarReserva(long roomId, String ownerEmail, LocalDate date, LocalTime start,
                                             LocalTime end, String topic, int participants) {
        Reserva r = new Reserva(proximoIdReserva++, roomId, ownerEmail, date, start, end, topic, participants,
                agoraTexto());
        reservasPorId.put(r.id, r);
        return r;
    }

    public synchronized Reserva buscarReserva(long id) {
        return reservasPorId.get(id);
    }

    public synchronized void removerReserva(long id) {
        reservasPorId.remove(id);
    }

    /** Todas as reservas, ordenadas por data e horario de inicio. */
    public synchronized List<Reserva> listarReservas() {
        List<Reserva> lista = new ArrayList<>(reservasPorId.values());
        lista.sort(Comparator.comparing((Reserva r) -> r.date).thenComparing(r -> r.start));
        return lista;
    }

    public synchronized List<Reserva> reservasDaSala(long roomId) {
        List<Reserva> lista = new ArrayList<>();
        for (Reserva r : listarReservas()) {
            if (r.roomId == roomId) lista.add(r);
        }
        return lista;
    }

    /**
     * Regra 5.4: conflito quando novo_inicio < fim_existente e novo_fim > inicio_existente.
     * Comecar exatamente no fim de outra NAO e conflito. excetoId = reserva sendo alterada, ou -1.
     */
    public synchronized Reserva conflito(long roomId, LocalDate date, LocalTime start, LocalTime end, long excetoId) {
        for (Reserva r : reservasPorId.values()) {
            if (r.id == excetoId || r.roomId != roomId || !r.date.equals(date)) continue;
            if (start.isBefore(r.end) && end.isAfter(r.start)) return r;
        }
        return null;
    }

    /** Nome atual do dono de uma reserva (o dono e guardado pelo email). */
    public synchronized String nomeDoDono(Reserva r) {
        Usuario u = usuariosPorEmail.get(r.ownerEmail);
        return u == null ? "" : u.user;
    }
}
