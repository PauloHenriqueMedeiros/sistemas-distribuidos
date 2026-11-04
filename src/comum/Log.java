package comum;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Impressao no terminal com horario. Sincronizado para as threads nao misturarem linhas.
 * Uma interface grafica pode se registrar como ouvinte para receber cada linha tambem.
 */
public final class Log {

    private Log() { }

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static volatile Consumer<String> ouvinte;

    /** A interface grafica recebe cada linha de log (ou null para parar). */
    public static void definirOuvinte(Consumer<String> novo) {
        ouvinte = novo;
    }

    public static synchronized void print(String etiqueta, String mensagem) {
        String linha = "[" + LocalTime.now().format(HORA) + "] " + etiqueta + " " + mensagem;
        System.out.println(linha);
        System.out.flush();
        Consumer<String> o = ouvinte;
        if (o != null) o.accept(linha);
    }
}
