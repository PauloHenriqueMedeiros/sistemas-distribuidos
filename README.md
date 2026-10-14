# Reserva de Salas — EP-1 (Sistemas Distribuídos · UTFPR-PG)

Cliente e servidor em Java puro (sem bibliotecas externas), via terminal, seguindo o **protocolo v2.4** da turma
(https://pedrosalvatori.github.io/protocolo-reserva-salas/): TCP + JSON em linha única terminado por `\n`,
UTF-8, máx. 8192 bytes, uma thread por cliente.

Operações implementadas: `register`, `login`, `logout`, `read_user`, `update_user`, `delete_user` e as do administrador
`admin_list_users`, `admin_read_user`, `admin_update_user`, `admin_delete_user`.
Qualquer outra `op` recebe `{"op": "error", "status": "400", "message": "Operacao desconhecida"}`.

## Estrutura

```
src/comum/     Json.java (leitor/escritor JSON), Protocolo.java (framing, regex), Log.java
src/servidor/  Servidor.java (main), ConexaoCliente.java (thread por cliente),
               Operacoes.java (regras do protocolo), BancoDados.java (onde os dados ficam guardados)
src/cliente/   Cliente.java (main, menu no terminal)
Servidor.jar / Cliente.jar   prontos para rodar (Java 11 ou mais novo)
```

## Como rodar

- Servidor: `java -jar Servidor.jar` (ou dois cliques em `servidor.bat`) → digite a porta.
- Cliente: `java -jar Cliente.jar` (ou `cliente.bat`) → digite o IP e a porta do servidor.

Pela IDE: abra a pasta, marque `src` como pasta de código-fonte e rode `servidor.Servidor` ou `cliente.Cliente`.
Se alterar o código, rode `compilar.bat` para gerar os `.jar` de novo.

O servidor mostra os IPs da máquina ao iniciar e cria um administrador padrão: `admin@email.com` / `admin123`.

O servidor é **só intermediário**: depois de ligar, o único comando no terminal dele é `sair`. Ele mostra no log
tudo o que recebe, responde e decide, mas toda consulta e alteração é feita pelo cliente.

## Como os dados são guardados

Tudo fica **em memória**, dentro do objeto `BancoDados` do servidor (nada é gravado em disco; ao fechar o
servidor, os dados somem):

| Estrutura | Tipo | Chave → valor | Para quê |
|---|---|---|---|
| `usuariosPorEmail` | `LinkedHashMap` | email → `Usuario{email, user, password, role, createdAt}` | o cadastro |
| `sessoesPorToken` | `HashMap` | token → `Sessao{email, ultimoUso}` | tokens ativos (30 min sem uso) |
| `tokensJaEmitidos` | `HashSet` | token | nenhum token é gerado duas vezes (regra 3.4) |

O email é a chave porque não pode ser alterado (RNF 5.c). Todo acesso passa por `synchronized`, então duas
threads nunca leem e gravam ao mesmo tempo (ex.: dois logins simultâneos → um 200 e um 409).

**Para ver o que está guardado**, faça login no cliente como admin. O menu ganha a seção ADMINISTRADOR:

| Opção | Operação | O que faz |
|---|---|---|
| 10 | `admin_list_users` | lista todos os cadastros (usuário, email, role) |
| 11 | `admin_read_user` | mostra um cadastro (usuário, email, role, data de criação) |
| 12 | `admin_update_user` | altera usuário, senha e/ou role de alguém (vazio = não alterar) |
| 13 | `admin_delete_user` | remove alguém (cadastro e sessão) |

Essas opções só aparecem para quem fez login como admin (o servidor também confere: se um usuário comum mandar
uma operação de admin, recebe 403 "Permissao insuficiente"). Senhas e tokens não aparecem, porque o protocolo não
devolve esses dados.

## Menu do cliente

- **Sem login:** 1 Cadastrar, 2 Login, 0 Sair (3 a 6 avisam que é preciso fazer login).
- **Logado:** 3 Ver meu cadastro, 4 Atualizar, 5 Apagar minha conta, 6 Logout, 0 Sair; o admin vê também 10 a 13.
  "Cadastrar" e "Login" somem e ficam bloqueados enquanto há alguém logado (é preciso fazer logout antes).

O IP e a porta do servidor são pedidos uma vez, ao abrir o cliente. Para usar outro servidor, feche e abra o cliente.

## Roteiro para a avaliação

Tudo o que é enviado e recebido aparece nos dois terminais (`ENVIADO ->` / `RECEBIDO <-`), e o servidor
explica cada decisão (ex.: `register RECUSADO (400): ['user' fora do formato ^[a-z]{1,30}$]`).

| Item | No cliente | Resposta esperada |
|---|---|---|
| a / g | 1 → `joao.silva@email.com`, `joao`, `senha123` | 201 Usuario cadastrado com sucesso |
| c / i | 2 → `joao.silva@email.com`, `senha123` | 200 + token + role |
| b / h | 3 | 200 com user, email, role, created_at |
| e / k | 4 → novo usuário `joaozinho`, senha em branco | 200; confira com a opção 3 |
| d / j | 6 | 200; o token é descartado |
| f / l | 2 (login de novo) → 5 → senha → `s` | 200 Usuario removido com sucesso |

Depois de cada passo, o admin pode conferir o efeito com a opção 10 (listar usuários). No admin: listar (10), ver um usuário (11), promover alguém a admin (12, role `admin`), remover alguém (13).
Erros de admin: usuário inexistente (404), novo nome já usado (409), rebaixar ou remover o último admin
(403 "Nao e possivel remover o ultimo administrador").

Casos de erro que valem mostrar: cadastro repetido (409),
usuário com número ou maiúscula (400), senha errada no login (401), login duplo (409: com
alguém logado num cliente, faça login na mesma conta em outro cliente), atualizar com os dois campos
vazios (400 "Nenhum dado para atualizar"), apagar a conta com a senha errada (403 "Senha incorreta", a sessão
continua).

O cliente também confere as respostas do servidor do colega e avisa quando algo foge do protocolo.

## Testador da turma

O repositório do protocolo tem um testador automático: `node scripts/testar-servidor.mjs 127.0.0.1 PORTA`.
Neste servidor, **todos os testes de cadastro, login, token, próprio cadastro, administrador, sessão e
remoção de conta passam** (46). Os 8 que falham são de salas e reservas (respondem "Operacao desconhecida"),
que são das próximas entregas.

## Mudanças da v2.0 para a v2.4 aplicadas

| Versão | Mudança | Onde |
|---|---|---|
| 2.1 | Nova regex de email (aceita `.com.br`, `.edu.br`, `alunos.utfpr.edu.br`, hífen, `_`) | `Protocolo.EMAIL` (cliente e servidor) |
| 2.1 | `delete_user` com senha errada → **403 "Senha incorreta"**; nada é apagado e a sessão continua | servidor; o cliente mantém o token no 403 |
| 2.1 | 401 sempre significa "token sem sessão" → o cliente sempre descarta o token no 401 | cliente |
| 2.2 | Conexão fechada pelo servidor (300 s parado) → o cliente reconecta e reenvia a mesma requisição | cliente |
| 2.2 | O token só é descartado depois de receber o `logout_response` | cliente |
| 2.2 | Login na ordem 400 → 401 → 409, e checar sessão + criar token no mesmo lock | servidor (já fazia; agora documentado) |
| 2.3 | Remover a conta apaga cadastro e sessão; `user` e `email` ficam livres para novo cadastro | servidor (já fazia) |
| 2.4 | `update_user` com `user` e `password` vazios → **400 "Nenhum dado para atualizar"** | servidor |
| 2.4 | `admin_update_user` com `user`, `password` e `role` vazios → o mesmo 400 | servidor |

## Decisões onde o protocolo ainda não é explícito

1. **Token ausente** → 400 (regra 2.10); o testador aceita 400 ou 401 (pendência 1).
2. **Maiúsculas em `user`/`email`** → 400 (não converte; as regex não aceitam maiúsculas, pendência 8).
3. **`update_user` com a chave `email`** (mesmo vazia) → 400 "Dados em formato invalido".
4. **Linha vazia** é ignorada; `\r\n` no final é aceito (clientes Windows que usam `println`).
