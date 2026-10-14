package comum;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Impressao no terminal com horario. Sincronizado para as threads nao misturarem linhas. */
public final class Log {

    private Log() { }

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static synchronized void print(String etiqueta, String mensagem) {
        System.out.println("[" + LocalTime.now().format(HORA) + "] " + etiqueta + " " + mensagem);
        System.out.flush();
    }
}
