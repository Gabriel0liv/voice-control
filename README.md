# VoiceControl Mod (Forge 1.20.1)

A server-side only Minecraft Forge mod that integrates with **Simple Voice Chat** to record player voice chat and package imported audios into a resource pack dynamically to be played using the vanilla `/playsound` command.

---

## 🛠️ Instalação

1. Cole o arquivo `.jar` gerado na pasta `mods/` do seu servidor Forge 1.20.1.
2. Certifique-se de que o mod **Simple Voice Chat** esteja instalado tanto no servidor quanto nos clientes que desejam falar e ouvir.
3. Inicie o servidor para gerar as pastas de trabalho e o arquivo de configuração.

---

## 📦 Dependências

* **Forge Loader**: Compatível com Minecraft 1.20.1.
* **Simple Voice Chat (Mod & API)**: Requer versão **2.6.x** instalada no servidor.
* **FFmpeg (Opcional, Altamente Recomendado)**: Necessário para a conversão automática de arquivos `.mp3` e `.wav` importados para `.ogg`. Caso não esteja disponível no PATH do sistema, o mod aceitará apenas arquivos `.ogg` já convertidos colocados diretamente na pasta.

---

## 📂 Estrutura de Pastas do Servidor

A pasta `voice-control/` é criada automaticamente na raiz do servidor:

* `voice-control/recordings/` - Pasta contendo todas as gravações ativas e concluídas.
  * `players/<nick>/` - Gravações de microfone individuais de players (Opus decodificado para MP3 ou WAV).
  * `monitors/<nick>/` - Gravações de áudio mixadas do que um player monitorado ouviu. Também contém a subpasta `speakers/<speakerNick>/` com a faixa separada de cada falante.
* `voice-control/imported-audios/` - Pasta onde administradores colocam arquivos (`.ogg`, `.wav`, `.mp3`) a serem importados.
* `voice-control/resourcepack/` - Pasta de trabalho utilizada para gerar a estrutura do resource pack.
  * `build/` - Contém o arquivo final `voicecontrol-pack.zip`.
  * `cache/` - Pasta de cache temporário.
* `voice-control/logs/` - Pasta contendo os logs administrativos do mod (`admin.log`).

---

## 💬 Comandos

Todos os comandos exigem permissão de operador de nível configurável (padrão: 3).

### Sistema de Gravação
* `/voicectl rec mic start <player>` - Inicia a gravação direta do microfone do jogador informado.
* `/voicectl rec mic start all` - Inicia a gravação direta do microfone de todos os jogadores online (e ativa auto-gravação para novos jogadores se configurado).
* `/voicectl rec mic stop <player>` - Para a gravação direta do microfone do jogador informado.
* `/voicectl rec mic stop all` - Para a gravação de todos os jogadores ativos.
* `/voicectl rec monitor start <player>` - Inicia a gravação monitorada (grava tudo o que o jogador/admin escuta, gerando faixas mixadas e individuais por falante).
* `/voicectl rec monitor stop <player>` - Interrompe a gravação monitorada do jogador informado.
* `/voicectl rec status` - Mostra a lista de todas as sessões de gravação ativas.

### Sistema de Áudio Importado
* `/voicectl audio reload` - Escaneia `voice-control/imported-audios/`, higieniza nomes, converte MP3/WAV para OGG se necessário, gera os arquivos `pack.mcmeta` e `sounds.json` e monta o resource pack `.zip`.
* `/voicectl audio list` - Lista todos os IDs de sons registrados no servidor.
* `/voicectl audio info <name>` - Mostra os detalhes de um áudio importado específico (caminho físico, tamanho, hash).

### Sistema de Resource Pack
* `/voicectl pack build` - Compacta e gera manualmente o resource pack `voicecontrol-pack.zip` e recalcula o hash.
* `/voicectl pack push` - Envia o pacote de recursos a todos os jogadores online usando pacotes vanilla.
* `/voicectl pack status` - Mostra o status do servidor HTTP interno do mod e o hash SHA-1 do pack atual.

---

## 🔊 Exemplos de `/playsound`

Uma vez que o áudio tenha sido carregado e o resource pack aplicado ao jogador, você pode reproduzir os áudios importados usando o comando vanilla do Minecraft:

* Se você colocou o arquivo `Boss Fala 1.mp3` na pasta de importação, ele é normalizado como `boss_fala_1` sob o namespace padrão `voicecontrol`.
* Para tocar o áudio para todos os jogadores:
  `/playsound voicecontrol:boss_fala_1 voice @a`
* Para tocar o áudio para um jogador específico na posição dele:
  `/playsound voicecontrol:boss_fala_1 voice playername ~ ~ ~`

---

## 🛜 Envio do Resource Pack e Servidor HTTP Interno

O mod possui um servidor HTTP interno leve embutido. Ele escuta na porta padrão `8087` (configurável) para servir o arquivo `voicecontrol-pack.zip`.

### Fluxo Recomendado de Atualização de Sons:
1. Coloque os novos arquivos de áudio na pasta `voice-control/imported-audios/`.
2. Execute o comando `/voicectl audio reload`. (O mod faz a conversão OGG, gera a estrutura e reconstrói o ZIP automaticamente).
3. Execute o comando `/voicectl pack push`. (O mod envia um pacote de recursos informando a URL e o novo Hash SHA-1. Os jogadores online aceitam/baixam o pack imediatamente).
4. Utilize o comando `/playsound voicecontrol:nome_do_som ...` para reproduzir.

### Configuração de IP/URL Pública:
Se o seu servidor Minecraft possui um IP público ou domínio, você deve configurar o parâmetro `publicUrl` em `config/voicecontrol-server.toml` para que os clientes consigam se conectar à porta HTTP. Exemplo:
```toml
publicUrl = "http://meuserver.com:8087/voicecontrol-pack.zip"
```
Se deixado em branco, o mod tentará utilizar a detecção automática baseada no IP de rede local da máquina host.

---

## ⚙️ Configuração Padrão (`config/voicecontrol-server.toml`)

O arquivo é gerado no diretório `config/` do seu servidor:

```toml
[recording]
    # Ativa/Desativa o sistema de gravação
    enabled = true
    # Formato padrão de saída das gravações (mp3 ou wav)
    defaultFormat = "mp3"
    # Formato de fallback caso o encoder MP3 falhe
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

[commands]
    # Nível de permissão necessário para usar os comandos do mod (op nível 3 ou 4)
    permissionLevel = 3

[audioImport]
    # Ativar sistema de importação de áudios
    enabled = true
    # Pasta contendo os arquivos a serem importados
    inputFolder = "voice-control/imported-audios"
    # Namespace ResourceLocation para os sons registrados
    namespace = "voicecontrol"
    # Tentar converter arquivos mp3/wav automaticamente para ogg usando ffmpeg
    convertToOgg = true

[resourcePack]
    # Ativar geração do resource pack
    resourcePackEnabled = true
    # Reconstruir o zip do resource pack automaticamente ao rodar audio reload
    autoBuildOnReload = true
    # Iniciar servidor HTTP interno para servir o pack
    internalHttpServer = true
    # Porta do servidor HTTP interno
    httpPort = 8087
    # URL de download pública do resource pack (deixe em branco para auto-detecção)
    publicUrl = ""
    # Se os jogadores devem obrigatoriamente instalar o pacote para jogar
    required = false
```

---

## 📄 Exemplo de `sounds.json` Gerado

```json
{
  "boss_fala_1": {
    "sounds": [
      "voicecontrol:boss_fala_1"
    ]
  },
  "evento_intro": {
    "sounds": [
      "voicecontrol:evento_intro"
    ]
  }
}
```

---

## ⚠️ Limitações Conhecidas

1. **Dependência do FFmpeg para Transcodificação**: O Java nativo não possui codificadores de OGG Vorbis internos. O mod busca a chamada `ffmpeg` no PATH do sistema. Sem ele instalado no servidor host, apenas arquivos `.ogg` pré-convertidos colocados manualmente serão aceitos na pasta `imported-audios`.
2. **Natives do LAME MP3**: O codificador de MP3 integrado usa a biblioteca nativa `Lame4J` provida pelo Simple Voice Chat. Em plataformas host raras ou não suportadas (como servidores baseados em processadores ARM antigos ou sem GLIBC atualizados no Linux), a inicialização do codificador MP3 pode falhar com erros JNI. O mod detecta e ativa o fallback automático para áudio bruto **WAV** sem travar o servidor.
3. **Multi-Track do Monitor**: O modo monitor mixa todos os participantes ouvidos em tempo real utilizando um buffer deslizante de PCM. O áudio mixado preserva o posicionamento estéreo e a distância dos players. As faixas de speakers individuais criam arquivos por falante perfeitamente sincronizados no tempo através da inserção automática de silêncios durante as pausas de fala.
