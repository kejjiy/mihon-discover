# Mihon Discover — Windows 0.1.2 et Android 0.20.4-discover.2

Première release publique du fork pour lire sur Windows 11 et retrouver ses
données Android sur le réseau local. Cette version est proposée en **preview** :
le lecteur Windows a été vérifié, mais un essai Android/PC sur un vrai réseau
reste à effectuer.

## Téléchargements

| Fichier | Plateforme | Usage |
| --- | --- | --- |
| `Mihon-Discover-Windows-0.1.2-Setup-x64.exe` | Windows 11, processeur x64 | Installeur avec assistant, raccourcis et désinstalleur. |
| `Mihon-Discover-Android-0.20.4-discover.2-universal.apk` | Android 8 ou plus récent | APK universel signé, version 32 ; mise à jour du fork existant. |
| `SHA256SUMS.txt` | Toutes | Sommes de contrôle des deux fichiers pour vérifier les téléchargements. |

Les archives **Source code** proposées par GitHub contiennent le code du projet,
pas les applications à installer.

## Installation Windows

1. Télécharger `Mihon-Discover-Windows-0.1.2-Setup-x64.exe` dans les fichiers de cette release.
2. Lancer l'assistant, choisir le dossier et terminer l'installation.
3. Ouvrir **Mihon Discover** depuis le Bureau ou le menu Démarrer.

L'installation concerne uniquement l'utilisateur courant et ne demande pas de
droits administrateur. L'assistant utilise le français ou l'anglais selon Windows.
Le programme Windows n'est pas signé par un certificat de publication : Windows
peut afficher un avertissement de réputation au premier lancement.

La bibliothèque des anciennes versions portables est conservée dans
`%APPDATA%/mihon-discover-windows/library/`. Installer cette version retrouve les
mêmes données. La désinstallation conserve aussi les données et les associations.

## Installation Android

1. Télécharger `Mihon-Discover-Android-0.20.4-discover.2-universal.apk` sur le téléphone.
2. Autoriser l'installation depuis l'application utilisée pour ouvrir l'APK si Android le demande.
3. Installer l'APK par-dessus l'ancienne version **Mihon Discover**.

L'identifiant de l'app (`io.github.kejjiy.mihondiscover`) et sa signature sont
conservés pour permettre la mise à jour du fork sans désinstallation préalable.
Il s'agit de ce fork, pas de l'app officielle Mihon.

## Nouveautés et lecture

- Lecteur Windows page par page ou webtoon ; import de CBZ/ZIP et dossiers d'images.
- Bibliothèque, catalogue AniList et premières recommandations locales.
- **Historique** des chapitres lus ou commencés, recherche par titre/chapitre,
  dates et bouton **Reprendre** à la page sauvegardée.
- Accès à la dernière lecture dans la bibliothèque.
- Plein écran webtoon sans les barres du haut et du bas, messages ou barre de
  défilement. **F** ou **Échap** restaure les commandes ; Échap quitte d'abord le
  plein écran avant de fermer le lecteur.
- Sur Android, récupération des lectures hors bibliothèque, y compris celles
  interrompues dès la première page.

## Synchronisation Android / Windows

1. Connecter les deux appareils au même réseau local.
2. Android : **Plus → Synchronisation locale → Découvrir mes appareils**.
3. Windows : **Synchronisation → Découvrir mes appareils**.
4. Choisir **Associer**, comparer les six chiffres et confirmer sur les deux appareils.
5. Choisir **Synchroniser** ou **Synchroniser mes appareils**, puis consulter **Historique** sur Windows.

La synchronisation échange la bibliothèque, les catégories, notes, chapitres,
marque-pages, progression, dates de lecture et données personnelles Discover.
Les échanges sont authentifiés et chiffrés. Aucun compte cloud n'est nécessaire.

Si la découverte échoue, saisir l'adresse `IPv4:port` affichée par l'autre app.
Autoriser Mihon Discover sur le **réseau privé** dans le pare-feu Windows si le
système le demande. Un réseau Wi-Fi invité, l'isolation des appareils ou un VPN
peut empêcher la découverte.

## Limites connues

- La lecture en ligne sur Windows passe par le téléphone associé : les extensions
  Android ne s'exécutent pas sur le PC. Les CBZ importés et les pages mises en
  cache restent lisibles hors ligne.
- Le téléphone doit garder l'écran de synchronisation ouvert pendant les échanges
  et la lecture distante. Aucun service Android permanent n'est encore fourni.
- Les comptes de trackers, identifiants, extensions, temps cumulés de lecture et
  files de téléchargement ne sont pas synchronisés.
- Les fichiers CBR/RAR et PDF, le transfert des CBZ Windows vers Android et certains
  téléchargements Android en archive ne sont pas encore pris en charge.
- L'app Windows de cette release cible x64 ; aucun installeur ARM64 natif n'est fourni.

## Vérifications

- Tests du protocole Kotlin/Node, association avec confirmation mutuelle,
  chiffrement, refus des messages rejoués et révocation.
- App Windows installée : lecture, sauvegarde, plein écran webtoon, historique
  synchronisé, recherche, reprise à la page sauvegardée et fermeture.
- Installation, réinstallation et désinstallation Windows ; vérification des
  raccourcis, de l'enregistrement Windows et de la conservation des données.
- APK Android : compilation release, vérification de la signature et correspondance
  du certificat avec la version précédente du fork.

Pour contrôler un téléchargement sous PowerShell :

```powershell
Get-FileHash .\Mihon-Discover-Windows-0.1.2-Setup-x64.exe -Algorithm SHA256
```

Comparer le résultat avec `SHA256SUMS.txt`. Le même contrôle s'applique à l'APK.

Documentation : [guide Windows et Android](https://github.com/kejjiy/mihon-discover/blob/discover-v0.1.2/desktop/README.md)
et [journal Windows](https://github.com/kejjiy/mihon-discover/blob/discover-v0.1.2/desktop/CHANGELOG.md).
