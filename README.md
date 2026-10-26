# Test Téléphone (appli Android)

Mesure : ping, débit descendant et montant, temps pour télécharger 50 Mo,
**vraies fréquences du processeur (GHz) lues dans Android**, vitesse et latence de la RAM, verdict (jeux, vidéo, visio).

## Obtenir l'APK : 2 méthodes

### Méthode A : Android Studio (ordinateur)
1. Installe Android Studio (gratuit).
2. Décompresse ce dossier, puis « Open » et choisis le dossier `TestTelephone`.
3. Attends la fin de « Gradle sync » (première fois : quelques minutes, il télécharge tout seul).
4. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Le fichier est ici : `app/build/outputs/apk/debug/app-debug.apk`.
6. Envoie-le sur ton téléphone et ouvre-le. Autorise « installer depuis cette source » si Android le demande.
   Play Protect peut afficher un avertissement : l'appli n'est pas publiée sur le Play Store, c'est normal.

### Méthode B : GitHub (compilation en ligne, rien à installer)
1. Crée un compte sur github.com puis un nouveau dépôt (« New repository »).
2. Envoie TOUT le contenu de ce dossier dans le dépôt, avec les sous-dossiers
   (y compris le dossier caché `.github`). Le plus simple : « Add file > Upload files » depuis un ordinateur.
3. Onglet **Actions** > « Compiler l'APK » > **Run workflow**.
4. Quand c'est vert (3 à 6 minutes), ouvre l'exécution et télécharge l'artefact **TestTelephone-apk**
   (un zip qui contient `app-debug.apk`).

## Remarques
- Version minimale d'Android : 8.0.
- Les GHz sont lus dans `/sys/devices/system/cpu/cpuN/cpufreq/`. Certains téléphones bloquent cette lecture :
  l'appli le dit alors clairement et affiche un calcul chronométré à la place.
- Le test consomme de la data : fais-le en Wi-Fi.
- Si la compilation affiche une erreur, copie le message et envoie-le : il sera facile à corriger.
