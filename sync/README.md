# Synchronisation locale Mihon Discover

Ce dossier contient le protocole commun aux clients Android et Windows.

- `protocol/` : module Kotlin JVM utilisé par l'app Android ; schéma des données,
  identités portables, fusion des révisions, chiffrement et tests.
- `fixtures/crypto.json` : clés et messages artificiels de test pour vérifier la
  compatibilité Kotlin/Node. Ces valeurs ne servent jamais aux associations réelles.
- `../desktop/src/protocol.cjs` : implémentation Node du même protocole.

La découverte utilise UDP multicast sur `239.255.77.77:41783`. L'association exige
une comparaison du code à six chiffres et une confirmation sur chaque appareil.
Les échanges TCP authentifiés et chiffrés transmettent les métadonnées ; les pages
du lecteur sont demandées séparément au téléphone associé.

Les chapitres portent leur état lu, la page atteinte et la dernière date de
lecture. La fusion conserve le statut lu, la progression maximale et la date la
plus récente. L'adaptateur Android inclut aussi les titres présents dans
l'historique hors bibliothèque, même si la lecture s'est arrêtée à la première page.

## Vérification

Depuis la racine du dépôt, avec Java 21 et le SDK Android configurés :

```powershell
./gradlew.bat :sync:protocol:test
```

Depuis `desktop/`, avec Node 22.12 ou ultérieur :

```powershell
npm ci
npm test
npm run smoke
```

Le [guide des clients](../desktop/README.md) décrit les conflits, les protections
réseau, les données synchronisées, les limites et le parcours d'association.
