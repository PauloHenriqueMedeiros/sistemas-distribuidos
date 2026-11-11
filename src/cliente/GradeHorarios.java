package cliente;

import comum.Aulas;
import comum.Visual;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Grade para marcar os horarios de funcionamento da sala (campo slots, protocolo v2.8):
 * colunas = dias (seg a sab), linhas = aulas da UTFPR (M1-M6, T1-T6, N1-N5).
 * Cada caixa marcada vira um codigo, ex.: segunda + M1 = "2M1".
 */
public final class GradeHorarios extends JPanel {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, JCheckBox> caixas = new LinkedHashMap<>();

    public GradeHorarios() {
        super(new BorderLayout(0, 8));
        setOpaque(false);

        JPanel grade = new JPanel(new GridBagLayout());
        grade.setBackground(Visual.CARTAO);
        grade.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Visual.BORDA), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 2, 0, 2);
        c.gridy = 0;
        c.gridx = 0;
        grade.add(new JLabel(""), c);
        for (int d = 0; d < Aulas.DIAS.length; d++) {
            c.gridx = d + 1;
            JLabel dia = Visual.rotulo(Aulas.NOMES_DIAS[d]);
            dia.setHorizontalAlignment(SwingConstants.CENTER);
            grade.add(dia, c);
        }
        String turnoAnterior = "M";
        int linha = 1;
        for (Aulas.Aula a : Aulas.TABELA) {
            String turno = a.codigo.substring(0, 1);
            c.gridy = linha++;
            c.insets = new Insets(turno.equals(turnoAnterior) ? 0 : 8, 2, 0, 2);
            turnoAnterior = turno;
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            JLabel rotulo = new JLabel(a.codigo + "  " + a.inicioTexto() + "-" + a.fimTexto());
            rotulo.setFont(Visual.FONTE_SUB);
            rotulo.setForeground(Visual.TEXTO_SUAVE);
            grade.add(rotulo, c);
            c.anchor = GridBagConstraints.CENTER;
            for (int d = 0; d < Aulas.DIAS.length; d++) {
                c.gridx = d + 1;
                JCheckBox caixa = new JCheckBox();
                caixa.setOpaque(false);
                caixa.setToolTipText(Aulas.NOMES_DIAS[d] + " " + a.codigo + " (" + a.inicioTexto() + "-" + a.fimTexto()
                        + ") = " + Aulas.DIAS[d] + a.codigo);
                caixas.put(Aulas.DIAS[d] + a.codigo, caixa);
                grade.add(caixa, c);
            }
        }

        JPanel atalhos = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        atalhos.setOpaque(false);
        atalhos.add(Visual.subtitulo("Seg a Sex:"));
        atalhos.add(atalho("Manha", "M"));
        atalhos.add(atalho("Tarde", "T"));
        atalhos.add(atalho("Noite", "N"));
        JButton limpar = Visual.botao("Limpar", "secundario");
        limpar.addActionListener(e -> caixas.values().forEach(x -> x.setSelected(false)));
        atalhos.add(limpar);

        add(grade, BorderLayout.CENTER);
        add(atalhos, BorderLayout.SOUTH);
    }

    /** Marca (ou desmarca, se ja estiver tudo marcado) o turno inteiro de segunda a sexta. */
    private JButton atalho(String texto, String turno) {
        JButton b = Visual.botao(texto, "secundario");
        b.addActionListener(e -> {
            List<JCheckBox> doTurno = new ArrayList<>();
            for (Map.Entry<String, JCheckBox> x : caixas.entrySet()) {
                char dia = x.getKey().charAt(0);
                if (dia >= '2' && dia <= '6' && x.getKey().substring(1, 2).equals(turno)) doTurno.add(x.getValue());
            }
            boolean todos = doTurno.stream().allMatch(JCheckBox::isSelected);
            doTurno.forEach(x -> x.setSelected(!todos));
        });
        return b;
    }

    /** Codigos marcados, na ordem dia -> tabela (ex.: ["2M1", "2M2", "3T1"]). */
    public List<String> selecionados() {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> x : caixas.entrySet()) {
            if (x.getValue().isSelected()) l.add(x.getKey());
        }
        l.sort((a, b) -> Integer.compare(Aulas.ordem(a), Aulas.ordem(b)));
        return l;
    }

    public void marcar(Collection<?> slots) {
        for (Map.Entry<String, JCheckBox> x : caixas.entrySet()) {
            x.getValue().setSelected(slots != null && slots.contains(x.getKey()));
        }
    }
}
