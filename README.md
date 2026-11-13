# Citizen Bedrock

Mod **serveur uniquement** (NeoForge 21.1.215, Minecraft 1.21.1) qui permet aux joueurs Bedrock
(Xbox, PlayStation, Switch, mobile, Windows) de rejoindre le serveur Citizen.

## Installation
1. Télécharger le dernier `citizenbedrock-x.x.x.jar` dans l'onglet **Releases**.
2. Le mettre dans le dossier `mods` du serveur (pas dans Citizen Updater : les joueurs PC n'en ont pas besoin).
3. Dans la gestion des ports BoxToPlay : port **30040** en TCP + UDP, port **30079** en UDP.
4. Redémarrer le serveur. Au premier démarrage, le mod télécharge Geyser et ViaProxy dans le dossier `citizenbedrock/`.

Les joueurs console se connectent à `mc312.boxtoplay.com`, port `30040`.

## Fichiers
- `config/citizenbedrock/citizenbedrock.json` : réglages (ports, adresse, conversions forcées).
- `config/citizenbedrock/key.pem` : clé secrète, **ne jamais la partager**.
- `citizenbedrock/runtime/` : programmes du pont (gérés automatiquement).

## Commandes (op)
- `/citizenbedrock statut` : état du pont et joueurs console connectés.
- `/citizenbedrock relancer` : redémarre le pont.

## Pour les autres mods Citizen
`fr.citizen.bedrock.BedrockPlayers.isBedrock(player)` indique si un joueur est sur console.
