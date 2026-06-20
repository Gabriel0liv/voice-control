# VoiceControl Mod (Forge 1.20.1)

Um mod client-server para Minecraft Forge 1.20.1 integrado com **Simple Voice Chat** que permite:
1. Gravar áudio de microfone de jogadores e monitorar sessões de áudio.
2. Importar e reproduzir sons dinâmicos client-side (sem resource pack) via comandos `/playsound` customizados.
3. Transmitir áudios importados diretamente como streams de voz no voice chat da vizinhança.

O mod passou de uma arquitetura baseada em Resource Packs para uma arquitetura de **Client-Server companion**. O mod deve estar instalado tanto no servidor quanto no cliente para que as funcionalidades de áudio dinâmico funcionem corretamente.

---

## 🛠️ Compilação e Instalação

### Compilando o Mod
Para compilar e gerar o arquivo `.jar` do mod a partir do código-fonte:

No Linux/macOS:
```bash
./gradlew clean build
```

No Windows:
```powershell
.\gradlew.bat clean build
```
O arquivo `.jar` compilado será gerado em `build/libs/`.

### Instalação no Servidor e Cliente
1. Cole o arquivo `.jar` gerado na pasta `mods/` do seu servidor Forge 1.20.1 e do seu cliente Minecraft.
2. Certifique-se de que o mod **Simple Voice Chat** esteja instalado tanto no servidor quanto no cliente.
3. Inicie o jogo/servidor.

---

## 📦 Dependências

* **Forge Loader**: Compatível com Minecraft 1.20.1 (Forge 47.4.10).
* **Simple Voice Chat (Mod & API)**: Requer versão **2.6.x** instalada.
* **FFmpeg (Opcional, Altamente Recomendado no Servidor)**: Necessário para decodificar arquivos `.ogg` para PCM 48kHz mono no servidor para reprodução via Voice Chat, bem como para conversão automática de `.mp3` e `.wav` importados para `.ogg`.

---

## 📂 Estrutura de Pastas de Trabalho

A pasta `voice-control/` é criada automaticamente na raiz do servidor e do cliente (`.minecraft/voice-control/`):

### Servidor:
* `voice-control/recordings/` - Pasta contendo todas as gravações ativas e concluídas.
  * `players/<nick>/<data>/<sessao>/` - Gravações de microfone individuais de players (`mic.mp3` ou `mic.wav`, `mic.json` de metadados e `mic.sha256`).
  * `monitors/<nick>/<data>/<sessao>/` - Gravações de áudio mixadas do que um jogador ouviu. Também contém a subpasta `speakers/<speakerNick>/` com a faixa separada de cada falante.
* `voice-control/imported-audios/` - Pasta onde administradores colocam arquivos de áudio (`.ogg`, `.wav`, `.mp3`) a serem importados.
* `voice-control/transcoded-cache/` - Pasta contendo cache de transcriação (arquivos convertidos).
* `voice-control/logs/` - Logs administrativos do mod (`admin.log`).

### Cliente:
* `voice-control/cache/` - Contém arquivos de áudio `.ogg` baixados do servidor e o manifest de cache local (`manifest.json`), indexados por SHA-256.

---

## 💬 Comandos

Todos os comandos exigem nível de permissão de operador de nível configurável (padrão: 3).

### 1. Sistema de Gravação (`/voicectl rec`)
* `/voicectl rec mic start <player>` - Inicia a gravação direta do microfone do jogador.
* `/voicectl rec mic start all` - Inicia a gravação de todos os jogadores online.
* `/voicectl rec mic stop <player>` - Para a gravação do microfone do jogador.
* `/voicectl rec mic stop all` - Para a gravação de todos os jogadores ativos.
* `/voicectl rec monitor start <player>` - Inicia gravação monitorada do que o jogador/admin escuta.
* `/voicectl rec monitor stop <player>` - Para a gravação monitorada do jogador.
* `/voicectl rec status` - Mostra a lista de sessões de gravação ativas.

### 2. Sincronização e Catálogo (`/voicectl audio`)
* `/voicectl audio reload` - Escaneia a pasta de importação, transcodifica se necessário, atualiza o manifest do servidor e notifica os clientes prontos para atualizar/baixar.
* `/voicectl audio list` - Lista todos os IDs de sons registrados no servidor.
* `/voicectl audio info <sound>` - Exibe detalhes de um áudio importado específico (nome, caminho, tamanho, SHA-256).
* `/voicectl audio sync <player>` - Sincroniza o catálogo de áudios com um jogador específico (se o companion dele estiver ativo).
* `/voicectl audio sync all` - Sincroniza o catálogo com todos os jogadores online que têm o companion ativo.

### 3. Motor de Áudio Dinâmico Client-Side (`/voicectl playsound` & `/voicectl stopsound`)
Estes comandos tocam e param áudios diretamente no hardware de som do cliente através de OpenAL, baixando os arquivos em chunks automaticamente se faltarem em cache.

* `/voicectl playsound <sound> <source> <targets> [pos] [volume] [pitch] [minVolume]`
  * Toca um som dinâmico personalizado. Se posicional, acompanha as coordenadas informadas 3D espacialmente.
  * *Alias:* `/vcplaysound ...`
* `/voicectl stopsound <targets> [source] [sound]`
  * Interrompe a execução dos sons dinâmicos nos alvos informados. Se `source` (categoria) ou `sound` (ID) forem informados, filtra para parar apenas os correspondentes.
  * *Alias:* `/vcstopsound ...`

### 4. Motor de Reprodução Simple Voice Chat (`/voicectl voiceplay` & `/voicectl voicestop`)
Estes comandos transmitem os áudios importados decodificados em PCM como transmissões de microfone dentro do voice chat posicional ou estático.

* `/voicectl voiceplay <sound> <targets>` - Transmite o áudio de forma estática 2D diretamente aos targets informados no voice chat.
* `/voicectl voiceplay <sound> at <x> <y> <z>` - Transmite o áudio de forma posicional 3D a partir das coordenadas informadas.
* `/voicectl voiceplay <sound> from <entity>` - Transmite o áudio posicional 3D a partir da entidade especificada.
* `/voicectl voiceplay stop <sound>` - Para a transmissão de voz de um som específico.
* `/voicectl voicestop <sound>` - Para a transmissão de voz de um som específico (principal).
* `/voicectl voicestop all` - Interrompe imediatamente todas as transmissões ativas via Voice Chat.
* *Alias:* `/vcvoicestop <sound|all>` - Alias para interromper transmissões via Voice Chat.

---

## ⚙️ Configurações (`config/voicecontrol-server.toml`)

O arquivo de configuração é gerado na primeira execução do servidor:

```toml
[recording]
    # Ativa/Desativa o sistema de gravação
    enabled = true
    # Formato padrão de saída das gravações (mp3 ou wav)
    defaultFormat = "mp3"
    # Formato de fallback caso o encoder padrão falhe
    fallbackFormat = "wav"
    # Salvar metadados JSON das gravações
    saveMetadata = true
    # Salvar arquivo contendo hash SHA-256 das gravações
    saveHash = true
    # Parar a gravação de um jogador quando ele desconectar
    autoStopOnDisconnect = true
    # Iniciar gravação de novos jogadores automaticamente ao usar "start all"
    autoRecordNewPlayersWhenAll = true
    # Duração máxima de gravação em minutos (0 para desativar limite)
    maxRecordingMinutes = 60
    # Organizar gravações em pastas estruturadas por data/sessão
    organizeByDateAndSession = true
    # Padrão da pasta de data
    dateFolderPattern = "dd-MM-yyyy"
    # Padrão da pasta de sessão
    sessionFolderPattern = "dd-MM-yyyy_HH-mm-ss"

[commands]
    # Nível de permissão OP necessário para rodar comandos
    permissionLevel = 3

[audioLibrary]
    # Ativa/Desativa o catálogo de áudios importados
    enabled = true
    # Nome da subpasta de importações dentro de voice-control/ (Ex: imported-audios)
    importFolder = "imported-audios"
    # Permitir conversão automática de MP3/WAV para OGG usando FFmpeg no servidor
    allowMp3WavTranscode = true
    # Caminho opcional do executável FFmpeg
    ffmpegPath = ""
    # Enviar catálogo (manifest) automaticamente quando o jogador conecta
    syncOnPlayerJoin = true
    # Re-sincronizar catálogo quando o reload do áudio for chamado
    syncOnAudioReload = true
    # Tamanho máximo de pacote de rede de transferência (em bytes)
    maxChunkSizeBytes = 32768
    # Tamanho máximo do arquivo físico importado aceitável (em MB)
    maxAudioFileSizeMb = 20
    # Extensões permitidas para importação
    allowedExtensions = ["ogg", "wav", "mp3"]

[dynamicSound]
    # Ativar motor de áudio dinâmico client-side
    enabled = true
    # Ativar gravação e carregamento de arquivos locais no cliente
    clientCacheEnabled = true
    # Volume padrão inicial
    defaultVolume = 1.0
    # Pitch padrão inicial
    defaultPitch = 1.0
    # Limite máximo de canais de som tocando simultaneamente no cliente
    maxConcurrentSounds = 32
    # Pre-download de todos os arquivos no carregamento do mundo
    preloadOnJoin = false
    # Executar áudio automaticamente ao concluir o download caso estivesse ausente
    playAfterDownloadIfMissing = true

[voicePlayback]
    # Ativar motor de transmissão no Simple Voice Chat
    enabled = true
    # Distância máxima de alcance padrão do som de voz (em blocos)
    defaultDistance = 48
    # Volume da transmissão padrão
    defaultVolume = 1.0
    # Duração máxima limite de áudio transmitido em segundos
    maxDurationSeconds = 120
    # Decodificar PCM completo para RAM ao realizar reload (evita decodificações em runtime)
    preDecodePcmOnReload = false
```
