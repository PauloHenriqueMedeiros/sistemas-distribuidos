# Reserva de Salas — EP-2 (Sistemas Distribuídos · UTFPR-PG)

Cliente e servidor em Java puro (sem bibliotecas externas), com interface gráfica Swing, seguindo o **protocolo v2.9.1** da turma
(https://pedrosalvatori.github.io/protocolo-reserva-salas/): TCP + JSON em linha única terminado por `\n`,
UTF-8, requisições de até 8192 bytes (respostas sem limite), uma thread por cliente.

O servidor implementa **as 21 operações do protocolo**: autenticação, próprio cadastro, administrador, salas e reservas.
No testador da turma (`node scripts/testar-servidor.mjs IP PORTA`), **101 de 102 testes passam e nenhum falha**: o que
sobra ("hoje, numa aula que já começou") o próprio testador só roda depois das 08:30.

## Estrutura

```
src/comum/     Json.java, Protocolo.java (framing, regex), Aulas.java (tabela de aulas da UTFPR e codigos 2M1),
               Log.java, Visual.java (cores e componentes das janelas)
src/servidor/  ServidorGUI.java (janela), Servidor.java (aceita conexoes), ConexaoCliente.java (thread por cliente),
               Operacoes.java (regras do protocolo), BancoDados.java (onde os dados ficam guardados)
src/cliente/   ClienteGUI.java (janela), GradeHorarios.java (grade dias x aulas), Rede.java (conexao, envio e
               conferencia das respostas)
Servidor.jar / Cliente.jar   prontos para rodar (Java 11 ou mais novo); abrem a interface grafica
```

## Como rodar

- **Servidor:** `java -jar Servidor.jar`. Digite a porta e clique em **Iniciar servidor**.
- **Cliente:** `java -jar Cliente.jar`. Digite o IP e a porta do servidor e clique em **Conectar**.

O terminal de onde o comando foi rodado também mostra as mensagens.

Pela IDE: marque `src` como pasta de código-fonte e rode `servidor.ServidorGUI` ou `cliente.ClienteGUI`.
Se alterar o código, rode `compilar.bat` para gerar os `.jar` de novo.

Ao iniciar, o servidor cria o **admin inicial** da regra 3.15: email `admin@admin.com`, senha `admin`
(igual em todos os servidores da turma).

## Interface

**Servidor:** só tem o campo de porta, **Iniciar servidor** e **Encerrar servidor**. O servidor é intermediário:
não há consultas nem cadastros por ele. A janela mostra os IPs da máquina (para o colega digitar no cliente) e o log
de tudo o que chega, sai e é decidido, com cores: recebido em azul, resposta de sucesso em verde, resposta de erro em
vermelho e o motivo de cada decisão em roxo.

**Cliente:** tela de conexão (IP e porta), depois um menu lateral que muda conforme o login:

| Menu | Quem vê | Operações |
|---|---|---|
| Entrar / Criar conta | só sem login | `login` / `register` |
| Meu cadastro | logado | `read_user`; alterar (`update_user`); apagar a conta (`delete_user`, pede a senha) |
| Salas | logado | `list_rooms` com filtro opcional de data, horário e capacidade mínima; **Ver agenda do dia** (`read_room` com `date`) |
| Reservar sala | logado | `create_reservation`; início e fim escolhidos na tabela de aulas da UTFPR |
| Minhas reservas | logado | `list_reservations` (scope mine) |
| Usuarios | admin | `admin_list_users`; Ver detalhes (`admin_read_user`), Alterar (`admin_update_user`), Remover (`admin_delete_user`) |
| Gerenciar salas | admin | salas com horários e status (`list_rooms`) e, para a sala selecionada, quem reservou e quando (`list_reservations` scope all); Agenda do dia (`read_room` com `date`); Nova sala (`create_room`, com a grade de horários), Alterar (`read_room` + `update_room`), Remover (`delete_room`) |
| Sair da conta | logado | `logout` |

- Embaixo de tudo fica o painel **Mensagens enviadas e recebidas**, com cada JSON que sai e chega (o divisor pode ser
  arrastado para aumentar o painel).
- O resultado de cada ação aparece numa faixa no topo: verde para sucesso, vermelha para erro, com o status e a message
  exatos do servidor.
- O cliente avisa no painel quando um campo está fora do formato, mas envia mesmo assim, para o servidor mostrar o erro dele.
- **Navegar pelo menu não envia nada ao servidor.** Cada tela só busca dados quando você clica em **Atualizar**
  (ou **Buscar**, na tela Salas, e **Atualizar lista de salas**, em Reservar sala). Depois de uma ação sua, como
  Salvar, Nova sala ou Remover, a lista daquela tela é recarregada para mostrar o resultado.
- Ao sair da conta, as telas são limpas, para o próximo usuário não ver os dados do anterior.
- As requisições rodam fora da thread da tela, então a janela nunca congela esperando o servidor.

## Roteiro para a avaliação (EP-2)

Antes: entre como `admin@admin.com` / `admin`, cadastre uma sala (Gerenciar salas → Nova sala) e saia. Crie uma conta
de usuário comum, entre com ela, reserve a sala (Reservar sala) e saia. Assim há um usuário comum e uma reserva para mostrar.

| Item | No cliente (logado como admin) | Resposta esperada |
|---|---|---|
| a / i | Usuarios → Atualizar | lista com todos os usuários |
| b / j | Usuarios → selecione o usuário comum → Alterar | 200 "Dados atualizados com sucesso" |
| c / k | Usuarios → selecione o usuário comum → Remover | 200 "Usuario removido com sucesso" (as reservas dele também somem) |
| d / l | Gerenciar salas → Nova sala → nome, capacidade, local, recursos e os horários na grade (dias × aulas) | 201 "Sala cadastrada com sucesso" + número da sala |
| e / m | Gerenciar salas → Atualizar → selecione a sala; **Agenda do dia** para um dia específico | horários de funcionamento, capacidade, status (Livre/Indisponivel) e quem reservou, em que dia e horário |
| f / n | Gerenciar salas → Alterar (nome, capacidade, local, recursos, horários, status) | 200 "Sala atualizada com sucesso" |
| g / o | Gerenciar salas → sala sem reservas futuras → Remover | 200 "Sala removida com sucesso"; ela some da tabela |
| h | casos de erro abaixo | mensagens de erro exatas do protocolo |

Erros que valem mostrar (item h): sala com nome repetido (409 "Sala ja cadastrada"), remover sala com reserva futura
(409 "Sala possui reservas ativas"), sala inexistente (404 "Sala nao encontrada"), capacidade inválida (400),
reservar horário ocupado (409 "Sala ja reservada no periodo solicitado"), reservar sala inativa
(409 "Sala indisponivel para reserva"), reservar num horário em que a sala não funciona (409 "Horario fora do
funcionamento da sala"), participantes acima da capacidade (400), reduzir a capacidade abaixo de uma reserva ativa
(409 "Capacidade menor que participantes de reservas ativas"), alterar sala sem mudar nada (400 "Nenhum dado para
atualizar"), remover ou rebaixar o último admin (403), usuário inexistente (404).

## Como os dados são guardados

Tudo fica **em memória**, no objeto `BancoDados` do servidor. Nada é gravado em disco; ao fechar o servidor, os dados somem.

| Estrutura | Tipo | Chave → valor |
|---|---|---|
| `usuariosPorEmail` | `LinkedHashMap` | email → `Usuario{email, user, password, role, createdAt}` |
| `sessoesPorToken` | `HashMap` | token → `Sessao{email, ultimoUso}` (30 min sem uso) |
| `tokensAdmin` | `HashSet` | tokens com poder de admin (whitelist, regra 3.14) |
| `tokensJaEmitidos` | `HashSet` | todo token já gerado (nunca se repete, regra 3.4) |
| `salasPorId` | `LinkedHashMap` | room_id → `Sala{name, capacity, location, resources, slots, status}`; `slots` são os horários de funcionamento no código da UTFPR (`2M1` = segunda, manhã, 1ª aula) |
| `reservasPorId` | `LinkedHashMap` | reservation_id → `Reserva{roomId, email do dono, date, start, end, topic, participants, createdAt}` |

Todo acesso passa por `synchronized (banco)`. Assim, verificar conflito e gravar a reserva acontecem na mesma seção
crítica (regra 5.1): duas reservas simultâneas no mesmo horário → uma 201 e uma 409.

## Horários das salas (protocolo v2.8)

Cada sala tem `slots`: os horários em que funciona, no código da UTFPR, **dia + turno + aula**.
Dia: 2 segunda … 7 sábado (domingo nunca funciona). Turno: M manhã, T tarde, N noite.

| turno | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| M | 07:30–08:20 | 08:20–09:10 | 09:10–10:00 | 10:20–11:10 | 11:10–12:00 | 12:00–12:50 |
| T | 13:00–13:50 | 13:50–14:40 | 14:40–15:30 | 15:50–16:40 | 16:40–17:30 | 17:30–18:20 |
| N | 18:40–19:30 | 19:30–20:20 | 20:20–21:10 | 21:20–22:10 | 22:10–23:00 | |

Uma reserva começa no início de uma aula e termina no fim de outra, e ocupa todas as aulas entre elas. Por exemplo,
13:00–14:40 numa terça ocupa `3T1` e `3T2`, e a sala precisa ter os dois nos `slots`; se não tiver, a resposta é
409 "Horario fora do funcionamento da sala".

## Mudanças aplicadas

| Versão | Mudança | Onde |
|---|---|---|
| 2.5 | Whitelist de tokens de admin: só ela libera `admin_*`, criar/alterar/remover sala, `scope: "all"` e ações em reservas de terceiros | servidor (`tokensAdmin`) |
| 2.6 | Admin inicial `admin` / `admin@admin.com` / `admin` quando não há nenhum admin | servidor |
| 2.7 / 2.8 | Salas com `slots` (código da UTFPR), obrigatório no `create_room` e devolvido no `read_room` e no `list_rooms` | servidor e cliente (grade dias × aulas) |
| 2.8 | Reservas e consultas só com início e fim de aula; fora dos `slots` → 409 "Horario fora do funcionamento da sala" e `available: "false"` | servidor e cliente (listas de horários) |
| 2.8 | `read_room` com `date` devolve a `agenda` do dia (livre/indisponível e quem reservou; `reservation_id` só para admin e dono) | servidor e cliente (Agenda do dia) |
| 2.9 | `resources` no `update_room`: `[]` mantém, `["0"]` apaga todos, lista nova substitui; cada recurso com regex e diferente de "0" | servidor e cliente |
| 2.9 | "Nenhum dado para atualizar" também no `update_room` e no `update_reservation` | servidor |
| 2.9 | Capacidade abaixo de reserva ativa → 409 "Capacidade menor que participantes de reservas ativas" | servidor |
| 2.9 | `update_reservation`: só o dono (inclusive para o admin); mudar horário em sala inativa → 409; novo início no passado → 400 | servidor |
| 2.9 | Ordem de validação obrigatória (seção 4 do contrato); data inexistente → 400; passado = data + hora de início; consultas aceitam datas passadas | servidor |
| 2.9.1 | Respostas sem limite de tamanho (o cliente lê até o `\n`) | cliente |
| 2.9.1 | `update_reservation` com `room_id` ou `date` → 400; `email` com até 100 caracteres; filtro opcional não preenchido é omitido | servidor e cliente |
