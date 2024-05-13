# keycloak-discord

Keycloak Social Login extension for Discord.


## Install

Download `keycloak-discord-<version>.jar` from [Releases page](https://github.com/wadahiro/keycloak-discord/releases).
Then deploy it into `$KEYCLOAK_HOME/providers` directory.

## Setup

### Discord

Access to [Discord Developer Portal](https://discord.com/developers/applications) and create your application.
You can get Client ID and Client Secret from the created application.

### Keycloak

Note: You don't need to setup the theme in `master` realm from v0.3.0.

1. Add `discord` Identity Provider in the realm which you want to configure.
2. In the `discord` identity provider page, set `Client Id` and `Client Secret`.
3. (Optional) Set Guild Id(s) to allow federation if you want.


### Syncing roles

To sync roles from Discord -> Keycloak, do the following:

1. Under the `discord` Identity Provider, fill out `Discord Roles mapping` value with the roles you want synced:
  - The format is `Discord-Guild-ID:Discord-Role-ID:Group-Name`, like so: `613425648685547541:613426529623605268:discord-devs-moderators`
  - You can specify multiple roles by separating them with commas: `613425648685547541:613426529623605268:discord-devs-moderators,613425648685547541:936746847437983786:discord-devs-modmail`
  - If you want to add a role just because the user is on the server, you can use the everyone role ID, which is the same as the guild ID : `613425648685547541:613425648685547541:everyone`
3. Set up a Mapper under the `discord` Identity Provider:
  - Set Mapper Type to `Claim to Group Mapper`
  - Set Claim to `discord-groups`
  - Tick Create Groups if not exists

If the above doesn't get role syncing working, fiddle around with the Sync mode override. (I have mine set to `Force`, so it re-imports info on every login)


## Source Build

Clone this repository and run `mvn package`.
You can see `keycloak-discord-<version>.jar` under `target` directory.


## Licence

[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)


## Author

- [Hiroyuki Wada](https://github.com/wadahiro)

