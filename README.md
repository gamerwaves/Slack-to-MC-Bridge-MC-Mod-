# Slack MC Bridge

## **IMPORTANT**

INSTALL THE MODRINTH APP AND THEN INSTALL [THIS MRPACK](https://github.com/gamerwaves/Slack-to-MC-Bridge-MC-Mod-/raw/refs/heads/main/Slack-MC-Bridge%201.0.0.mrpack) INTO A NEW INSTANCE BY DOING THIS: NEW INSTANCE -> FROM FILE!!!

## What this is

This Slack to MC bridge takes slack messages sent in a channel and sends it to minecraft like this:

`[Slack] <DISPLAY NAME> Message`

It also takes MC messages and sends them to slack with your minecraft skin as your pfp.

## Usage

Download the mod from [Github Releases](https://github.com/gamerwaves/Slack-to-MC-Bridge-MC-Mod-/releases) and then put `slackbridge-x.x.x` into your mods folder.

Then, run your server for the first time with this mod, and then stop it, got to the servers config/slackbridge.json/ and then fill in all values. Finally, restart your server and follow the link instructions, then test it out by going to the slack channel and typing somthing in mc or slack.

## Developing

Clone this repo, and then create a slack bot using this manifest:

```manifest.json
{
    "display_information": {
        "name": "Slack MC Bridge",
        "description": "Slack MC Bridge",
        "background_color": "#b80000"
    },
    "features": {
        "bot_user": {
            "display_name": "Slack to MC Bridge",
            "always_online": false
        },
        "slash_commands": [
            {
                "command": "/list",
                "description": "Checks online players",
                "should_escape": false
            },
            {
                "command": "/whois",
                "description": "Checks info of mc players.",
                "usage_hint": "[player]",
                "should_escape": false
            },
            {
                "command": "/link",
                "description": "Link MC to Slack Account",
                "usage_hint": "[code]",
                "should_escape": false
            },
            {
                "command": "/unlink",
                "description": "Unlinks MC account",
                "should_escape": false
            },
            {
                "command": "/whisper",
                "description": "Sends a private message to one or more players.",
                "usage_hint": "[player(s) seperated by commas] [message]",
                "should_escape": false
            }
        ]
    },
    "oauth_config": {
        "scopes": {
            "bot": [
                "channels:history",
                "channels:join",
                "chat:write",
                "chat:write.customize",
                "chat:write.public",
                "commands",
                "im:history",
                "incoming-webhook",
                "users:read",
                "emoji:read"
            ]
        }
    },
    "settings": {
        "event_subscriptions": {
            "bot_events": [
                "message.channels",
                "message.im"
            ]
        },
        "interactivity": {
            "is_enabled": true
        },
        "org_deploy_enabled": true,
        "socket_mode_enabled": true,
        "token_rotation_enabled": false
    }
}
```

Run `./gradlew build`

Then, run your server for the first time with this mod with `./gradlew runServer`, and then stop it, got to the servers config/slackbridge.json/ and then fill in all values.

Finally, restart your server and follow the link instructions, then test it out by going to the slack channel and typing somthing in mc or slack.
