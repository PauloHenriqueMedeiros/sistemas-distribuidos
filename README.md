# Reserva de Salas — EP-2 (Sistemas Distribuídos · UTFPR-PG)

Cliente e servidor em Java puro (sem bibliotecas externas), via terminal, seguindo o **protocolo v2.6** da turma
(https://pedrosalvatori.github.io/protocolo-reserva-salas/): TCP + JSON em linha única terminado por `\n`,
UTF-8, máx. 8192 bytes, uma thread por cliente.

O servidor implementa **as 21 operações do protocolo**: autenticação, próprio cadastro, administrador, salas e reservas.
No testador da turma (`node scripts/testar-servidor.mjs IP PORTA`), **79 de 79 testes passam**.

## Estrutura

```
src/comum/     Json.java (leitor/escritor JSON), Protocolo.java (framing, regex), Log.java
src/servidor/  Servidor.java (main), ConexaoCliente.java (thread por cliente),
               Operacoes.java (regras do protocolo), BancoDados.java (onde os dados ficam guardados)
src/cliente/   Cliente.java (main, menu no terminal)
Servidor.jar / Cliente.jar   prontos para rodar (Java 11 ou mais novo)
```

## Como rodar

- Servidor: `java -jar Servidor.jar` (ou `servidor.bat`) → digite a porta.
- Cliente: `java -jar Cliente.jar` (ou `cliente.bat`) → digite o IP e a porta do servidor.

Pela IDE: marque `src` como pasta de código-fonte e rode `servidor.Servidor` ou `cliente.Cliente`.
Se alterar o código, rode `compilar.bat` para gerar os `.jar` de novo.

Ao iniciar, o servidor cria o **admin inicial** da regra 3.15: email `admin@admin.com`, senha `admin`
(igual em todos os servidores da turma). Depois de ligado, o único comando dele é `sair`; tudo é feito pelo cliente,
e o log do servidor mostra cada mensagem recebida, enviada e o motivo de cada decisão.

## Menu do cliente

| Opção | Quem | Operação |
|---|---|---|
| 1 Cadastrar / 2 Login | só sem login | `register` / `login` |
| 3 Ver meu cadastro / 4 Atualizar / 5 Apagar / 6 Logout | logado | `read_user` / `update_user` / `delete_user` / `logout` |
| 7 Listar salas | logado | `list_rooms` (filtro opcional de horário e capacidade) |
| 8 Reservar uma sala | logado | `create_reservation` |
| 9 Minhas reservas | logado | `list_reservations` (scope mine) |
| 10 a 13 | admin | `admin_list_users`, `admin_read_user`, `admin_update_user`, `admin_delete_user` |
| 20 Cadastrar sala | admin | `create_room` |
| 21 Ver todas as salas e reservas | admin | `list_rooms` + `list_reservations` (scope all) |
| 22 Ver uma sala e sua agenda | admin | `read_room` + `list_reservations` (scope all, room_id) |
| 23 Alterar sala | admin | `read_room` + `update_room` |
| 24 Remover sala | admin | `delete_room` |

As opções de admin só aparecem quando o login foi de um admin. O servidor confere de verdade pela whitelist de tokens:
quem não é admin recebe 403 "Permissao insuficiente".

## Roteiro para a avaliação (EP-2)

Antes: entre como `admin@admin.com` / `admin`, cadastre uma sala (20), saia (6), cadastre e entre com um usuário comum
(1, 2), reserve a sala (8) e saia. Assim há um usuário comum e uma reserva para mostrar.

| Item | No cliente (logado como admin) | Resposta esperada |
|---|---|---|
| a / i | 10 | 200 com todos os usuários |
| b / j | 12 → usuário comum → novo nome/senha/role | 200 "Dados atualizados com sucesso" |
| c / k | 13 → usuário comum → `s` | 200 "Usuario removido com sucesso" (as reservas dele também somem) |
| d / l | 20 → nome, capacidade, local, recursos | 201 "Sala cadastrada com sucesso" + room_id |
| e / m | 21 (ou 22 para uma sala) | salas com capacidade, status (livre/indisponível) e, embaixo de cada uma, quem reservou, data e horário |
| f / n | 23 → nome, capacidade, local, recursos, status (`active` = livre, `inactive` = indisponível) | 200 "Sala atualizada com sucesso" |
| g / o | 24 → sala sem reservas futuras → `s` | 200 "Sala removida com sucesso"; confira com 21 |
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
