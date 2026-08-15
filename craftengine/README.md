# EscapeFromCraftov CraftEngine resource pack

This directory is the source of the optional tactical presentation layer used by both Paper
servers. CraftEngine is deliberately presentation-only: PostgreSQL/PDC `ItemInstance` identity,
inventory ownership, grid placement and settlement remain authoritative in EscapeFromCraftov.

## Runtime version

- CraftEngine Community `26.7.4`
- Paper `1.21.11`
- Plugin file: `craft-engine-paper-plugin-26.7.4.jar`
- SHA-1: `179e7b8b191093e41beca56e3d52ae608f46e161`
- SHA-512:
  `1d6982e325552fd51a9a481252e0c055c32a5b6e3c2d816cf69ca4b2f355a5f7af1abed00ff9d29c08fb32ff293d9d11837ad4372c34675b73682a98b1ec86b0`

Install the same plugin Jar and copy `resources/escapefromcraftov` into
`plugins/CraftEngine/resources/` on Lobby and Game. Lobby is the sole resource-pack sender;
Game builds the same pack for model/font consistency but keeps `send-on-join: false`.

For both servers, disable CraftEngine's ZIP anti-extraction default so generated packs remain
standards-compliant and inspectable:

```yaml
resource-pack:
  protection:
    crash-tools:
      method-1: false
      method-2: false
      method-3: false
      method-4: false
      method-5: false
      method-6: false
      method-7: false
      method-8: false
    incorrect-crc: false
    fake-file-size: false
    fake-directory: false
    break-texture: false
```

The current Velocity proxy is intentionally in offline mode, so Lobby uses both
`strict-player-uuid-validation: false` and self-host `strict_validation: false`. Local dual-client
testing uses the dedicated `http://127.0.0.1:30302/` endpoint; replace that URL with a genuinely
client-reachable address before allowing remote players. Game disables automatic upload and pack
delivery; Lobby keeps self-hosting, automatic upload and `send-on-join: true`. Packet interceptors
are not required: item models use Paper's native
`item_model` component and the Sidebar uses fixed private-use glyphs in
`escapefromcraftov:hud`.

## Stable identifiers

Item models:

- `escapefromcraftov:basic_rig`
- `escapefromcraftov:patrol_backpack`
- `escapefromcraftov:secure_case`

Font glyphs:

- `U+E001`: Sidebar insignia
- `U+E010..U+E018`: map, time, squad, combat, carry, weight, value, target and extraction

Do not renumber these glyphs or model IDs after release. Existing serialized Bukkit stacks and
runtime Components refer to them directly.

## Validation

On a stopped or disposable server:

```text
/ce reload all
/ce resource list
/ce debug image escapefromcraftov:hud_insignia
```

The log must report one enabled resource, two images and three items. With all `crash-tools`
methods disabled, `unzip -t plugins/CraftEngine/generated/resource_pack.zip` must report no
errors. The archive must contain the three `assets/escapefromcraftov/items/*.json` models,
`assets/escapefromcraftov/font/hud.json`, and their PNG textures.

## Artwork provenance

The committed SVG/PNG artwork is original project artwork, designed as a dark graphite/olive
tactical interface with restrained cyan accents. An attempted `gpt-image-2` generation could not
run because the local `OPENAI_API_KEY` was not a valid OpenAI API key, so no generated image was
represented as an AI output. The deterministic SVG sources under `art/` are the editable masters;
`art/item-preview.png` is a contact sheet for visual review.
