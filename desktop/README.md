# Mihon Discover pour Windows 11

Client Windows x64 de ce fork Android. Le lecteur Windows est implémenté dans
`desktop/`; l'app Android reste dans `app/`. Le protocole Kotlin et ses tests sont
dans `sync/protocol/`; les données cryptographiques de test communes sont dans
`sync/fixtures/`.

## Utilisation

Les fichiers d'installation sont disponibles dans les
[releases du fork](https://github.com/kejjiy/mihon-discover/releases).
Sur Windows 11 x64, télécharger le fichier `Setup-x64.exe` et suivre l'assistant.
L'installation concerne l'utilisateur courant, permet de choisir le dossier et
crée des raccourcis Bureau/menu Démarrer. La version `Portable-x64.exe` peut aussi
être compilée pour un lancement sans installation.

Pour Android, télécharger l'APK universel de la même release et l'installer
par-dessus la version précédente de ce fork. La signature Android est conservée.

1. Lancer Mihon Discover sur Windows et installer l'APK Android **de ce fork**.
   Les anciennes versions de l'APK ne disposent pas de ce protocole.
2. Connecter les appareils au même réseau local. Une liaison Ethernet pour le PC
   et Wi-Fi pour le téléphone convient si le routeur permet leurs échanges.
3. Sur Android : **Plus → Synchronisation locale → Découvrir mes appareils**.
   Sur Windows : **Synchronisation → Découvrir mes appareils**.
4. Cliquer **Associer** sur un appareil, comparer les six chiffres sur les deux
   écrans, puis confirmer sur les deux. Le code n'est pas un mot de passe envoyé
   sur le réseau : chaque appareil le calcule à partir du secret de cette session.
5. Cliquer **Synchroniser** ou **Synchroniser mes appareils**. Les associations
   restent enregistrées. La découverte doit être réactivée à chaque session.
6. Sur Windows, ouvrir un titre puis un chapitre. Le téléphone fournit les images
   depuis sa source ou un téléchargement en dossier. **Télécharger** conserve
   toutes les pages du chapitre sur le PC pour la lecture hors ligne.

Les CBZ/ZIP et dossiers d'images importés sur Windows fonctionnent sans téléphone.
Le lecteur propose le mode page par page, le mode webtoon, les deux sens de lecture,
les flèches du clavier et le plein écran (`F`). En plein écran webtoon, les barres
du haut et du bas, les messages et la barre de défilement sont masqués. `F` ou
`Échap` quitte le plein écran et réaffiche les commandes ; un second `Échap`
revient à l'écran précédent. La page courante est sauvegardée.

L'onglet **Historique** récupère les chapitres lus ou commencés lors de la
synchronisation, y compris les titres hors bibliothèque. Il affiche la date,
le chapitre et la page atteinte, du plus récent au plus ancien. Clique sur
**Reprendre** pour rouvrir ce chapitre à la page sauvegardée. Une recherche permet
de retrouver une ancienne lecture. La bibliothèque propose aussi un accès à la
dernière lecture. Les lectures sans date enregistrée sont affichées à la fin.

Si la découverte ne trouve rien, saisir l'adresse `IPv4:port` affichée par l'autre
app. Autoriser l'app dans le pare-feu Windows **sur le réseau privé** si Windows
le demande. Les réseaux invités, l'isolation Wi-Fi ou un VPN peuvent empêcher
les échanges. Le téléphone doit rester sur l'écran de synchronisation : cette
version ne lance pas de service Android permanent en arrière-plan. L'écran reste
allumé pendant que la découverte est active, puis retrouve son comportement normal.

Le binaire Windows est un build local non signé par un certificat de publication.

## Données et conflits

Les mangas sont identifiés par `(sourceId, url)`, les chapitres par leur manga et
leur URL. Les IDs de sources sont transmis comme chaînes pour conserver les
entiers 64 bits. Les IDs de la base de données locale ne sont pas réutilisés sur
l'autre appareil.

- Bibliothèque, favoris, notes, genres, métadonnées, catégories et réglages de
  lecture par titre.
- Chapitres, marque-pages, progression et dernière date de lecture.
- Discover : avis, états de lecture explicites, éléments masqués, liens source
  confirmés, politique romance et filtres enregistrés.

Chaque élément porte une révision `(modifiedAt, deviceId)`. Le journal Android
détecte les changements effectués par le lecteur et les écrans existants. Les
modifications les plus récentes gagnent, avec l'ID d'appareil comme départage
déterministe. Les modifications concurrentes à des champs différents du **même
élément** peuvent donc nécessiter de réappliquer une note ou un favori ; ce n'est
pas une fusion de texte. La progression et le statut lu sont fusionnés par maximum
et union pour ne jamais perdre une lecture. Remettre un chapitre à zéro sur un
appareil ne remet pas les autres à zéro. Les horloges des appareils doivent être
correctes (tolérance réseau de cinq minutes).

Une désinscription de la bibliothèque est un changement `favorite=false`.
La suppression des données personnelles Discover produit des marqueurs de
suppression propagés à la prochaine synchronisation. Supprimer complètement un
manga ou ses fichiers n'efface pas les copies présentes sur les autres appareils.
Les images sont mises en cache séparément et ne font pas partie de la fusion
des métadonnées : elles sont transférées à la demande du lecteur.

Avant chaque fusion, une copie locale permet de retrouver l'état précédent :
`library.json.before-sync` dans le dossier de données Windows,
`lan-sync-before-merge.json` dans les fichiers privés Android. Le journal Android
est `lan-sync-journal.json`. L'export JSON Windows est une sauvegarde des
métadonnées ; il ne contient ni les images ni les clés d'association.

## Réseau et association

La découverte UDP multicast utilise `239.255.77.77:41783`, TTL 1. L'annonce ne
contient que l'identité du fork, la version du protocole, un ID d'appareil opaque,
son nom, sa plateforme, son port TCP et un identifiant éphémère de session réseau.
Elle ne contient aucun titre ni progression.
Le serveur TCP choisit un port disponible et reçoit une trame JSON précédée de
quatre octets de longueur, au plus 16 Mio. L'accès est limité aux adresses locales.

L'association utilise ECDH P-256, HKDF-SHA256 et un code de comparaison à six
chiffres. Les deux confirmations sont obligatoires, l'association expire en deux
minutes et les demandes sont limitées. Vérifier les codes protège contre un
intermédiaire lors de cette association. Une confirmation sans comparaison annule
cette garantie. Une association autorise la synchronisation et l'accès aux pages
de la bibliothèque ; elle n'expire pas automatiquement. **Oublier** révoque cette
autorisation localement. Oublier aussi l'association sur l'autre appareil pour une
nouvelle association.

Les requêtes et réponses utilisent AES-256-GCM avec identité et direction comme
données authentifiées, nonce aléatoire, ID de requête unique et rejet des replays.
L'identifiant de session réseau change à chaque démarrage de la découverte ; un
message capturé avant un redémarrage n'est pas accepté après celui-ci.
Les clés persistantes sont protégées par Android Keystore et Electron safeStorage
(chiffrement Windows). Le renderer Electron est sandboxé, isolé de Node, avec une
CSP et une petite API IPC qui valide l'émetteur. Les fichiers importés ne peuvent
pas extraire des chemins arbitraires d'une archive.

## Limites de cette première version

- Les APK d'extensions Android ne s'exécutent pas sur Windows. La lecture en ligne
  passe par le téléphone. Il n'y a pas encore de moteur de sources Windows
  autonome, ni de transfert inverse des CBZ du PC vers le lecteur Android.
- Les téléchargements Android stockés en archive et les sources locales avec un
  chargeur spécifique peuvent demander une adaptation supplémentaire. Les sources
  HTTP et les téléchargements en dossiers sont pris en charge par l'adaptateur.
- Les comptes de trackers, secrets, sessions web, préférences propres au système,
  temps cumulés de lecture, files de téléchargement et binaires d'extensions ne
  sont pas synchronisés. Le cache AniList est propre à chaque appareil.
- Le catalogue Windows propose recherche/pays/genre/tri et avis locaux. Son
  classement « Pour toi » est un premier classement par genres/avis/qualité ;
  l'ensemble des filtres et le moteur de recommandation Kotlin Android ne sont
  pas encore reproduits à l'identique.
- Les images sont limitées à 8 Mio, les chapitres à 3000 pages et les snapshots à
  100 000 éléments et 16 Mio par échange. CBR/RAR et PDF ne sont pas pris en charge.

## Développement et vérification

Node 22.12 ou ultérieur :

```powershell
cd desktop
npm ci
npm start
npm test
npm run smoke
npm run dist
npm run smoke:installer
```

`npm run smoke` lance le véritable renderer Electron avec une bibliothèque
temporaire, vérifie lecture/progression/association/synchronisation sur loopback
et produit des captures dans `test-output/`. Il n'accède pas à la bibliothèque
réelle. Le runtime Electron récent se télécharge au premier lancement si nécessaire.

Android :

```powershell
./gradlew.bat :sync:protocol:test :app:assembleDebug
```

Les tests communs vérifient la compatibilité Kotlin/Node des clés ECDH, des codes
et du chiffrement. Les tests TCP vérifient la confirmation mutuelle, le rejet des
messages rejoués et la révocation. Le parcours sur un **véritable téléphone et un
autre appareil du réseau** reste à valider : aucun téléphone n'était connecté à
l'environnement de développement pendant cette implémentation.

Les exécutables sont produits dans `desktop/dist/` : `Setup-x64.exe` pour
l'installation et `Portable-x64.exe` pour la version portable. `npm run
dist:installer` ou `npm run dist:portable` construit un seul format.
Le test `smoke:installer` effectue une installation/réinstallation dans un dossier
isolé du dépôt, teste l'app installée et la désinstalle. Il refuse de démarrer si
une installation réelle de Mihon Discover est déjà présente.

L'état persistant reste dans `%APPDATA%/mihon-discover-windows/library/` pour les
deux formats ; passer du portable à l'installeur retrouve donc la même bibliothèque.
Déplacer l'exécutable ne déplace pas la bibliothèque. La désinstallation conserve
les données et les associations.
