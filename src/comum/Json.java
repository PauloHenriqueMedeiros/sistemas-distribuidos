package comum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor e escritor de JSON minimo, sem nenhuma biblioteca externa
 * (assim o projeto roda em qualquer maquina que tenha Java instalado).
 *
 * Tipos usados na leitura:
 *   objeto  -> Map<String, Object> (LinkedHashMap, mantem a ordem das chaves)
 *   array   -> List<Object>
 *   string  -> String
 *   numero  -> BigDecimal
 *   true/false -> Boolean
 *   null    -> null (use map.containsKey() para diferenciar "ausente" de "null")
 */
public final class Json {

    /** Erro de sintaxe no JSON recebido. */
    public static final class ErroJson extends Exception {
        private static final long serialVersionUID = 1L;

        public ErroJson(String mensagem) {
            super(mensagem);
        }
    }

    private final String txt;
    private int pos = 0;

    private Json(String txt) {
        this.txt = txt;
    }

    // =====================================================================
    // LEITURA
    // =====================================================================

    public static Object ler(String texto) throws ErroJson {
        if (texto == null) {
            throw new ErroJson("texto nulo");
        }
        Json leitor = new Json(texto);
        leitor.pularEspacos();
        Object valor = leitor.lerValor();
        leitor.pularEspacos();
        if (leitor.pos < leitor.txt.length()) {
            throw new ErroJson("conteudo extra depois do JSON (posicao " + leitor.pos + ")");
        }
        return valor;
    }

    private int atual() {
        return pos < txt.length() ? txt.charAt(pos) : -1;
    }

    private static boolean digito(int c) {
        return c >= '0' && c <= '9';
    }

    private void pularEspacos() {
        while (pos < txt.length()) {
            char c = txt.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }

    private void esperar(char c) throws ErroJson {
        if (atual() != c) {
            throw new ErroJson("esperado '" + c + "' na posicao " + pos);
        }
        pos++;
    }

    private Object lerValor() throws ErroJson {
        int c = atual();
        if (c == -1) throw new ErroJson("fim inesperado do texto");
        if (c == '{') return lerObjeto();
        if (c == '[') return lerLista();
        if (c == '"') return lerString();
        if (c == 't') { lerPalavra("true"); return Boolean.TRUE; }
        if (c == 'f') { lerPalavra("false"); return Boolean.FALSE; }
        if (c == 'n') { lerPalavra("null"); return null; }
        if (c == '-' || digito(c)) return lerNumero();
        throw new ErroJson("caractere inesperado '" + (char) c + "' na posicao " + pos);
    }

    private void lerPalavra(String palavra) throws ErroJson {
        if (!txt.startsWith(palavra, pos)) {
            throw new ErroJson("valor invalido na posicao " + pos);
        }
        pos += palavra.length();
    }

    private Map<String, Object> lerObjeto() throws ErroJson {
        esperar('{');
        Map<String, Object> objeto = new LinkedHashMap<>();
        pularEspacos();
        if (atual() == '}') {
            pos++;
            return objeto;
        }
        while (true) {
            pularEspacos();
            if (atual() != '"') {
                throw new ErroJson("esperado nome de campo entre aspas na posicao " + pos);
            }
            String chave = lerString();
            pularEspacos();
            esperar(':');
            pularEspacos();
            objeto.put(chave, lerValor());
            pularEspacos();
            int c = atual();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return objeto; }
            throw new ErroJson("esperado ',' ou '}' na posicao " + pos);
        }
    }

    private List<Object> lerLista() throws ErroJson {
        esperar('[');
        List<Object> lista = new ArrayList<>();
        pularEspacos();
        if (atual() == ']') {
            pos++;
            return lista;
        }
        while (true) {
            pularEspacos();
            lista.add(lerValor());
            pularEspacos();
            int c = atual();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return lista; }
            throw new ErroJson("esperado ',' ou ']' na posicao " + pos);
        }
    }

    private String lerString() throws ErroJson {
        esperar('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= txt.length()) {
                throw new ErroJson("string sem aspas de fechamento");
            }
            char c = txt.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw new ErroJson("caractere de controle dentro de string na posicao " + (pos - 1));
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= txt.length()) {
                throw new ErroJson("sequencia de escape incompleta");
            }
            char e = txt.charAt(pos++);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > txt.length()) {
                        throw new ErroJson("escape unicode incompleto");
                    }
                    int codigo = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(txt.charAt(pos + i), 16);
                        if (d < 0) throw new ErroJson("escape unicode invalido na posicao " + pos);
                        codigo = codigo * 16 + d;
                    }
                    sb.append((char) codigo);
                    pos += 4;
                    break;
                default:
                    throw new ErroJson("escape invalido na posicao " + (pos - 1));
            }
        }
    }

    private BigDecimal lerNumero() throws ErroJson {
        int inicio = pos;
        if (atual() == '-') pos++;
        if (atual() == '0') {
            pos++;
        } else if (digito(atual())) {
            while (digito(atual())) pos++;
        } else {
            throw new ErroJson("numero invalido na posicao " + inicio);
        }
        if (atual() == '.') {
            pos++;
            if (!digito(atual())) throw new ErroJson("numero invalido na posicao " + inicio);
            while (digito(atual())) pos++;
        }
        if (atual() == 'e' || atual() == 'E') {
            pos++;
            if (atual() == '+' || atual() == '-') pos++;
            if (!digito(atual())) throw new ErroJson("numero invalido na posicao " + inicio);
            while (digito(atual())) pos++;
        }
        return new BigDecimal(txt.substring(inicio, pos));
    }

    // =====================================================================
    // ESCRITA (sempre em linha unica, sem quebras de linha)
    // =====================================================================

    public static String escrever(Object valor) {
        StringBuilder sb = new StringBuilder();
        escreverValor(sb, valor);
        return sb.toString();
    }

    private static void escreverValor(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            escreverString(sb, (String) v);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean primeiro = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!primeiro) sb.append(", ");
                primeiro = false;
                escreverString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                escreverValor(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean primeiro = true;
            for (Object item : (List<?>) v) {
                if (!primeiro) sb.append(", ");
                primeiro = false;
                escreverValor(sb, item);
            }
            sb.append(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
        } else {
            escreverString(sb, v.toString());
        }
    }

    private static void escreverString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
