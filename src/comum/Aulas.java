package comum;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Tabela de aulas da UTFPR e o codigo de horario das salas (protocolo v2.8, secao 8).
 *
 * Codigo = dia + turno + aula. Ex.: 2M1 = segunda, manha, 1a aula; 4T3 = quarta, tarde, 3a aula.
 *   dia: 2 segunda ... 7 sabado (domingo nunca funciona)
 *   turno: M manha (1-6), T tarde (1-6), N noite (1-5)
 */
public final class Aulas {

    private Aulas() { }

    /** Uma aula da tabela, sem o dia: turno + numero (ex.: T1), com inicio e fim. */
    public static final class Aula {
        public final String codigo; // "T1"
        public final LocalTime inicio;
        public final LocalTime fim;

        Aula(String codigo, String inicio, String fim) {
            this.codigo = codigo;
            this.inicio = LocalTime.parse(inicio);
            this.fim = LocalTime.parse(fim);
        }

        public String inicioTexto() { return inicio.toString(); }
        public String fimTexto() { return fim.toString(); }
    }

    /** Na ordem da tabela: manha, tarde, noite. */
    public static final List<Aula> TABELA = Collections.unmodifiableList(Arrays.asList(
            new Aula("M1", "07:30", "08:20"), new Aula("M2", "08:20", "09:10"), new Aula("M3", "09:10", "10:00"),
            new Aula("M4", "10:20", "11:10"), new Aula("M5", "11:10", "12:00"), new Aula("M6", "12:00", "12:50"),
            new Aula("T1", "13:00", "13:50"), new Aula("T2", "13:50", "14:40"), new Aula("T3", "14:40", "15:30"),
            new Aula("T4", "15:50", "16:40"), new Aula("T5", "16:40", "17:30"), new Aula("T6", "17:30", "18:20"),
            new Aula("N1", "18:40", "19:30"), new Aula("N2", "19:30", "20:20"), new Aula("N3", "20:20", "21:10"),
            new Aula("N4", "21:20", "22:10"), new Aula("N5", "22:10", "23:00")));

    /** Dias com codigo: 2 = segunda ... 7 = sabado. */
    public static final String[] DIAS = {"2", "3", "4", "5", "6", "7"};
    public static final String[] NOMES_DIAS = {"Seg", "Ter", "Qua", "Qui", "Sex", "Sab"};

    /** start_time aceito: inicio de alguma aula (regra 8.3). */
    public static boolean ehInicio(String hora) {
        for (Aula a : TABELA) if (a.inicioTexto().equals(hora)) return true;
        return false;
    }

    /** end_time aceito: fim de alguma aula (regra 8.3). */
    public static boolean ehFim(String hora) {
        for (Aula a : TABELA) if (a.fimTexto().equals(hora)) return true;
        return false;
    }

    public static List<String> inicios() {
        List<String> l = new ArrayList<>();
        for (Aula a : TABELA) l.add(a.inicioTexto());
        return l;
    }

    public static List<String> fins() {
        List<String> l = new ArrayList<>();
        for (Aula a : TABELA) l.add(a.fimTexto());
        return l;
    }

    /** Aula que comeca nesse horario (ou null). */
    public static Aula pelaHoraDeInicio(String hora) {
        for (Aula a : TABELA) if (a.inicioTexto().equals(hora)) return a;
        return null;
    }

    /** Aula que termina nesse horario (ou null). */
    public static Aula pelaHoraDeFim(String hora) {
        for (Aula a : TABELA) if (a.fimTexto().equals(hora)) return a;
        return null;
    }

    /** Codigo do dia da semana: segunda "2" ... sabado "7"; domingo null (nunca funciona). */
    public static String codigoDoDia(LocalDate data) {
        DayOfWeek d = data.getDayOfWeek();
        return d == DayOfWeek.SUNDAY ? null : String.valueOf(d.getValue() + 1);
    }

    /** Aulas ocupadas por [inicio, fim): todas entre elas, inclusive atravessando o intervalo (regra 8.3). */
    public static List<Aula> aulasEntre(LocalTime inicio, LocalTime fim) {
        List<Aula> l = new ArrayList<>();
        for (Aula a : TABELA) {
            if (!a.inicio.isBefore(inicio) && !a.fim.isAfter(fim)) l.add(a);
        }
        return l;
    }

    /**
     * Regra 8.4: todas as aulas ocupadas, no dia da semana da data, precisam estar nos slots da sala.
     * Domingo nunca esta no funcionamento.
     */
    public static boolean dentroDoFuncionamento(Collection<String> slots, LocalDate data, LocalTime inicio, LocalTime fim) {
        String dia = codigoDoDia(data);
        if (dia == null) return false;
        List<Aula> ocupadas = aulasEntre(inicio, fim);
        if (ocupadas.isEmpty()) return false;
        for (Aula a : ocupadas) {
            if (!slots.contains(dia + a.codigo)) return false;
        }
        return true;
    }

    /** Posicao na ordem dia -> tabela (para ordenar codigos como 2M1, 2M2, 3T1...). */
    public static int ordem(String slot) {
        int dia = slot.charAt(0) - '0';
        String aula = slot.substring(1);
        for (int i = 0; i < TABELA.size(); i++) {
            if (TABELA.get(i).codigo.equals(aula)) return dia * 100 + i;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Resumo legivel dos slots: "Seg M1-M3, T1-T2 | Qua N1-N5".
     * Aulas seguidas do mesmo turno viram uma faixa.
     */
    public static String resumo(Collection<?> slots) {
        if (slots == null || slots.isEmpty()) return "nenhum";
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < DIAS.length; d++) {
            List<String> partes = new ArrayList<>();
            String turno = null;
            int ini = -1;
            int ant = -1;
            for (int i = 0; i <= TABELA.size(); i++) {
                Aula a = i < TABELA.size() ? TABELA.get(i) : null;
                boolean tem = a != null && slots.contains(DIAS[d] + a.codigo);
                String t = a == null ? null : a.codigo.substring(0, 1);
                if (tem && turno != null && t.equals(turno) && i == ant + 1) {
                    ant = i;
                    continue;
                }
                if (turno != null) {
                    String p = TABELA.get(ini).codigo;
                    if (ant != ini) p += "-" + TABELA.get(ant).codigo;
                    partes.add(p);
                    turno = null;
                }
                if (tem) {
                    turno = t;
                    ini = i;
                    ant = i;
                }
            }
            if (!partes.isEmpty()) {
                if (sb.length() > 0) sb.append("  |  ");
                sb.append(NOMES_DIAS[d]).append(' ').append(String.join(", ", partes));
            }
        }
        return sb.length() == 0 ? "nenhum" : sb.toString();
    }
}
