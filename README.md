# Reserva de Salas — EP-2 (Sistemas Distribuídos · UTFPR-PG)

Cliente e servidor em Java puro (sem bibliotecas externas), via terminal, seguindo o **protocolo v2.6** da turma
(https://pedrosalvatori.github.io/protocolo-reserva-salas/): TCP + JSON em linha única terminado por `\n`,
UTF-8, máx. 8192 bytes, uma thread por cliente.

O servidor implementa **as 21 operações do protocolo**: autenticação, próprio cadastro, administrador, salas e reservas.
No testador da turma (`node scripts/testar-servidor.mjs IP PORTA`), **79 de 79 testes passam**.

## Estrutura

```
src/comum/     Json.java, Protocolo.java (framing, regex), Log.java, Visual.java (cores e componentes das janelas)
src/servidor/  ServidorGUI.java (janela), Servidor.java (terminal), ConexaoCliente.java (thread por cliente),
               Operacoes.java (regras do protocolo), BancoDados.java (onde os dados ficam guardados)
src/cliente/   ClienteGUI.java (janela), Rede.java (conexao e envio usados pela janela), Cliente.java (terminal)
Servidor.jar / Cliente.jar   prontos para rodar (Java 11 ou mais novo); abrem a interface grafica
```

## Como rodar

- **Servidor:** dois cliques em `Servidor.jar` (ou `servidor.bat`). Digite a porta e clique em **Iniciar servidor**.
- **Cliente:** dois cliques em `Cliente.jar` (ou `cliente.bat`). Digite o IP e a porta do servidor e clique em **Conectar**.

O `servidor.bat` e o `cliente.bat` abrem a janela e deixam um terminal aberto atrás, que mostra as mesmas mensagens.
As versões de terminal continuam disponíveis: `servidor-terminal.bat` e `cliente-terminal.bat`.

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
| Salas | logado | `list_rooms` com filtro opcional de data, horário e capacidade mínima |
| Reservar sala | logado | `create_reservation` (a lista de salas vem do `list_rooms`) |
| Minhas reservas | logado | `list_reservations` (scope mine) |
| Usuarios | admin | `admin_list_users`; Ver detalhes (`admin_read_user`), Alterar (`admin_update_user`), Remover (`admin_delete_user`) |
| Gerenciar salas | admin | salas com status (`list_rooms`) e, para a sala selecionada, quem reservou e quando (`list_reservations` scope all); Nova sala (`create_room`), Alterar (`read_room` + `update_room`), Remover (`delete_room`) |
| Sair da conta | logado | `logout` |

- Embaixo de tudo fica o painel **Mensagens enviadas e recebidas**, com cada JSON que sai e chega (o divisor pode ser
  arrastado para aumentar o painel).
- O resultado de cada ação aparece numa faixa no topo: verde para sucesso, vermelha para erro, com o status e a message
  exatos do servidor.
- O cliente avisa no painel quando um campo está fora do formato, mas envia mesmo assim, para o servidor mostrar o erro dele.
- As requisições rodam fora da thread da tela, então a janela nunca congela esperando o servidor.

## Roteiro para a avaliação (EP-2)

Antes: entre como `admin@admin.com` / `admin`, cadastre uma sala (Gerenciar salas → Nova sala) e saia. Crie uma conta
de usuário comum, entre com ela, reserve a sala (Reservar sala) e saia. Assim há um usuário comum e uma reserva para mostrar.

| Item | No cliente (logado como admin) | Resposta esperada |
|---|---|---|
| a / i | Usuarios | lista com todos os usuários |
| b / j | Usuarios → selecione o usuário comum → Alterar | 200 "Dados atualizados com sucesso" |
| c / k | Usuarios → selecione o usuário comum → Remover | 200 "Usuario removido com sucesso" (as reservas dele também somem) |
| d / l | Gerenciar salas → Nova sala | 201 "Sala cadastrada com sucesso" + número da sala |
| e / m | Gerenciar salas → selecione a sala | capacidade, status (Livre/Indisponivel) e, embaixo, quem reservou, data e horário |
| f / n | Gerenciar salas → Alterar (nome, capacidade, local, recursos, status) | 200 "Sala atualizada com sucesso" |
| g / o | Gerenciar salas → sala sem reservas futuras → Remover | 200 "Sala removida com sucesso"; ela some da tabela |
| h | casos de erro abaixo | mensagens de erro exatas do protocolo |

Erros que valem mostrar (item h): sala com nome repetido (409 "Sala ja cadastrada"), remover sala com reserva futura
(409 "Sala possui reservas ativas"), sala inexistente (404 "Sala nao encontrada"), capacidade inválida (400),
reservar horário ocupado (409 "Sala ja reservada no periodo solicitado"), reservar sala inativa
(409 "Sala indisponivel para reserva"), participantes acima da capacidade (400), remover ou rebaixar o último admin (403),
usuário inexistente (404).

## Como os dados são guardados

Tudo fica **em memória**, no objeto `BancoDados` do servidor. Nada é gravado em disco; ao fechar o servidor, os dados somem.

| Estrutura | Tipo | Chave → valor |
|---|---|---|
| `usuariosPorEmail` | `LinkedHashMap` | email → `Usuario{email, user, password, role, createdAt}` |
| `sessoesPorToken` | `HashMap` | token → `Sessao{email, ultimoUso}` (30 min sem uso) |
| `tokensAdmin` | `HashSet` | tokens com poder de admin (whitelist, regra 3.14) |
| `tokensJaEmitidos` | `HashSet` | todo token já gerado (nunca se repete, regra 3.4) |
| `salasPorId` | `LinkedHashMap` | room_id → `Sala{name, capacity, location, resources, status}` |
| `reservasPorId` | `LinkedHashMap` | reservation_id → `Reserva{roomId, email do dono, date, start, end, topic, participants, createdAt}` |

Todo acesso passa por `synchronized (banco)`. Assim, verificar conflito e gravar a reserva acontecem na mesma seção
crítica (regra 5.1): duas reservas simultâneas no mesmo horário → uma 201 e uma 409.

## Mudanças da v2.4 para a v2.6 aplicadas

| Versão | Mudança | Onde |
|---|---|---|
| 2.5 | Whitelist de tokens de admin: só ela libera `admin_*`, criar/alterar/remover sala, `scope: "all"` e ações em reservas de terceiros. Entra no login de admin ou na promoção; sai no rebaixamento, logout, expiração e remoção, na hora | servidor (`tokensAdmin`) |
| 2.5 | O `role` do `login_response` só monta a tela do cliente | cliente (já era assim) |
| 2.6 | Admin inicial `admin` / `admin@admin.com` / `admin` quando não há nenhum admin | servidor (antes era `admin@email.com` / `admin123`) |

## Pontos que o protocolo não cobre (levar para a turma)

1. **Dias da semana e horários da sala** (itens d, f da EP-2): o protocolo não tem esses campos no `create_room` nem no
   `update_room`. A sala tem nome, capacidade, local, recursos e status; os horários aparecem pelas reservas
   (quem reservou, dia e horário). Não inventei campos para não quebrar a comunicação com os outros grupos.
2. **409 de capacidade no `update_room`** (pendência 3): o protocolo não define a message. Usei
   "Capacidade menor que os participantes de uma reserva futura".
3. **`update_reservation` pelo admin** (pendência 10): seguindo o contrato, só o dono altera; o admin lê e cancela qualquer uma.
4. **Data passada** em reserva: recusa se o início (data + hora) já passou.
5. **Remover sala**: só sem reservas futuras; as reservas já encerradas daquela sala são apagadas junto.
6. Token ausente → 400; maiúsculas em `user`/`email` → 400; linha vazia é ignorada; `\r\n` é aceito.
