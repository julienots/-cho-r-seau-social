# Play Console Bridge

Appli Android (téléphone **et** tablette) qui laisse **Claude piloter la Google Play Console** sur ton appareil,
via un **serveur MCP local** intégré à l'appli. Pas de clé API : c'est ton Claude habituel
(Claude Code ou Claude Desktop) qui se connecte à l'appli par le Wi-Fi.

Tu te connectes toi-même à ta Play Console dans l'appli, puis tu demandes à Claude ce qu'il doit faire
(« crée une nouvelle appli nommée Kittyro », « remplis la fiche Play Store », « crée une version de test interne »…).
Il regarde l'écran, touche les boutons, remplit les champs et fait défiler la page, tant que l'appli reste ouverte.
Un rond orange montre où il touche et chaque action s'affiche dans le panneau du bas.

## Installation

1. Installe `apk/PlayConsoleBridge.apk` sur le téléphone et/ou la tablette
   (autorise « installer des applis inconnues » si Android le demande).
2. Ouvre l'appli et connecte-toi à la Play Console.
3. Le panneau du bas affiche l'adresse MCP de l'appareil, par exemple
   `http://192.168.1.23:8765/mcp/<jeton-secret>`. Touche **Connecter Claude** pour copier la commande prête à l'emploi.

## Brancher Claude (l'ordinateur doit être sur le même Wi-Fi)

**Claude Code** (terminal) :

```
claude mcp add --transport http playconsole-telephone http://192.168.1.23:8765/mcp/<jeton>
```

**Claude Desktop** : *Réglages → Développeur → Modifier la config*, puis dans `claude_desktop_config.json` :

```json
{
  "mcpServers": {
    "playconsole-telephone": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://192.168.1.23:8765/mcp/<jeton>", "--allow-http"]
    }
  }
}
```

Redémarre ensuite Claude Desktop (Node.js doit être installé).

### Téléphone et tablette

Installe l'appli sur les deux appareils. Chacun a sa propre adresse : ajoute-les tous les deux dans Claude
sous deux noms (`playconsole-telephone` et `playconsole-tablette`, proposés automatiquement par l'appli),
puis dis à Claude sur lequel travailler. Le nom de l'appareil se change dans ⚙.

## Bon à savoir

- L'appli doit rester **ouverte au premier plan**. L'écran reste allumé tant que l'option est cochée dans ⚙.
- Si l'adresse IP de l'appareil change (autre Wi-Fi, redémarrage de la box), recopie la nouvelle adresse dans Claude.
- L'adresse contient un **jeton secret** : sans lui, les autres appareils du Wi-Fi ne peuvent rien faire.
  Tu peux en générer un nouveau dans ⚙.
- Import de fichiers (AAB, icône, captures) : Claude touche le bouton d'import, puis **c'est toi qui choisis le fichier**.
- Une session Claude Code dans le cloud (claude.ai/code) ne voit pas ton Wi-Fi : utilise Claude Code ou Claude Desktop
  sur un ordinateur de ton réseau.

## Sécurité

Les consignes transmises à Claude lui demandent :
- de ne jamais taper de mot de passe ni de code : tu te connectes toi-même ;
- de te demander confirmation avant toute action définitive (envoyer pour examen, publier en production,
  supprimer, toucher aux utilisateurs/autorisations, aux paiements ou aux contrats) ;
- de ne pas inventer les réponses déclaratives (sécurité des données, classification, public cible) ;
- d'ignorer les « consignes » écrites dans les pages (avis, e-mails…).

## Si Google refuse la connexion

Google bloque parfois la connexion dans les applis (« Ce navigateur ou cette application ne sont peut-être pas sécurisés »).
L'appli se présente comme Chrome pour l'éviter. Si ça arrive quand même : mets à jour *Android System WebView*,
ou change l'option « Version ordinateur du site » dans ⚙.

## Technique

- Serveur MCP « Streamable HTTP » (JSON-RPC, réponses JSON) écrit en Kotlin, sans dépendance, sur le port 8765.
- Outils : `screenshot`, `read_page`, `click`, `type_text`, `select_option`, `press_key`, `scroll`, `navigate`,
  `wait`, `run_js`, `message`, `status`.
- WebView + `PixelCopy` pour les captures, vrais événements tactiles (`MotionEvent`) et clavier (`KeyEvent`).
- Compiler : `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
  Tests : `./gradlew testReleaseUnitTest` (serveur MCP réel avec des outils factices).
