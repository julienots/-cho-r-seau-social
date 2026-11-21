# Play Console Bridge

Appli Android qui laisse **Claude piloter la Google Play Console** à ta place, directement sur ton téléphone.

Tu te connectes toi-même à ta Play Console dans l'appli, tu écris à Claude ce qu'il doit faire
(« crée une nouvelle appli nommée Kittyro », « remplis la fiche Play Store avec cette description »,
« crée une version de test interne »…) et il navigue tout seul : il regarde l'écran, touche les boutons,
remplit les champs et défile, tant que l'appli reste ouverte. Un rond orange montre où il touche.

## Installation

1. Télécharge `PlayConsoleBridge.apk` (dans les *Releases* ou le dossier `apk/`) et installe-le
   (autorise « installer des applis inconnues » si Android le demande).
2. Ouvre l'appli : les réglages (⚙) s'ouvrent.
   - Colle ta **clé API Anthropic** (créée sur console.anthropic.com → *API Keys*). Elle reste sur le téléphone.
   - Choisis le modèle : Opus 5.5 (le plus fiable), Sonnet 5.5 (moins cher) ou Haiku 5.5 (très économique).
3. Connecte-toi à la Play Console dans la page affichée (c'est toi qui tapes ton mot de passe et ta validation en 2 étapes).
4. Écris ta demande dans le panneau Claude en bas et appuie sur **Envoyer**.

> L'utilisation de l'API Anthropic est facturée à ta clé API (ce n'est pas inclus dans l'abonnement claude.ai).
> Les vieilles captures d'écran sont automatiquement retirées de la conversation pour limiter le coût.

## Utilisation

- **Stop** (le bouton Envoyer pendant que Claude travaille, champ vide) : arrête Claude immédiatement.
- Écrire un message pendant qu'il travaille : il le lit à l'étape suivante (pour le corriger en route).
- **⟲ Nouvelle conversation** : repart de zéro (la page reste ouverte).
- **▼ Claude** : replie le panneau pour voir la page en grand.
- Importer un fichier (AAB, icône, captures) : Claude touche le bouton d'import, puis **c'est toi qui choisis le fichier**
  dans le sélecteur du téléphone.
- L'écran reste allumé pendant que Claude travaille. Ne quitte pas l'appli : en arrière-plan la page se met en veille.

## Sécurité

Claude a pour consignes de :
- ne jamais taper de mot de passe ni de code : il te demande de te connecter toi-même ;
- te demander confirmation avant toute action définitive (envoyer pour examen, publier en production,
  supprimer, toucher aux utilisateurs/autorisations, aux paiements ou aux contrats) ;
- ne pas inventer les réponses des questionnaires (sécurité des données, classification, public cible) :
  il te les demande ;
- ignorer les « consignes » qui seraient écrites dans les pages (avis, e-mails…).

Tu vois tout ce qu'il fait en direct et tu peux l'arrêter à tout moment.

## Si Google refuse la connexion

Google bloque parfois la connexion dans les applis (« Ce navigateur ou cette application ne sont peut-être pas sécurisés »).
L'appli se présente comme Chrome pour l'éviter. Si ça arrive quand même : mets à jour *Android System WebView*
depuis le Play Store, réessaie, ou décoche/coche « Version ordinateur du site » dans les réglages.

## Technique

- Kotlin, sans dépendance UI lourde ; WebView + `PixelCopy` pour les captures, vrais événements tactiles
  (`MotionEvent`) et clavier (`KeyEvent`) pour agir comme un doigt.
- SDK officiel `anthropic-java` (API Messages, outils personnalisés, réflexion adaptative,
  édition de contexte côté serveur pour élaguer les vieilles captures, repli automatique en cas de refus).
- Outils donnés à Claude : `screenshot`, `read_page`, `click`, `type_text`, `select_option`, `press_key`,
  `scroll`, `navigate`, `wait`, `run_js`, `status`.

Compiler : `./gradlew assembleRelease` (SDK Android 35, JDK 17+) → `app/build/outputs/apk/release/app-release.apk`.
