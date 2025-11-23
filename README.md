# Slack to MC Bridge (Slack Bot)

## What this is

This Slack to MC bridge takes slack messages sent in a channel and sends it to minecraft like this: 

`[Slack] <DISPLAY NAME> Message`

It also takes MC messages and sends them to slack with your minecraft skin as your pfp.

## How to use this

Clone this repo, and then create a slack bot using this manifest:
```manifest.json
{
    "display_information": {
        "name": "Slack MC Bridge",
        "description": "Slack MC Bridge",
        "background_color": "#000000"
    },
    "features": {
        "bot_user": {
            "display_name": "Slack MC Bridge",
            "always_online": false
        }
    },
    "oauth_config": {
        "scopes": {
            "bot": [
                "channels:history",
                "channels:join",
                "users:read",
                "chat:write",
                "chat:write.customize",
                "chat:write.public",
                "im:history"
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
        "org_deploy_enabled": false,
        "socket_mode_enabled": true,
        "token_rotation_enabled": false
    }
}
```

Then, run your server for the first time with this mod, and then stop it, got to the servers config/slackbridge.json/ and then fill in all values.

Finally, restart your server and then test it out by going to the slack channel and typing somthing in mc or slack.
