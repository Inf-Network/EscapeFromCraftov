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
`escapefromcraftov:hud`. The Game server additionally enables
`network.intercept-packets.container: true` so CraftEngine can render the carry menu's
`<image>`/`<shift>` title tags; Lobby can keep that interceptor disabled.

## Stable identifiers

Item models:

- `escapefromcraftov:basic_rig`
- `escapefromcraftov:patrol_backpack`
- `escapefromcraftov:secure_case`

Font glyphs:

- `U+E001`: Sidebar insignia
- `U+E010..U+E018`: map, time, squad, combat, carry, weight, value, target and extraction
- `U+E020`: 176x222 tactical carry GUI for the 9x6 chest screen

Do not renumber these glyphs or model IDs after release. Existing serialized Bukkit stacks and
runtime Components refer to them directly.

## Validation

On a stopped or disposable server:

```text
/ce reload all
/ce resource list
/ce debug image escapefromcraftov:hud_insignia
```

The log must report one enabled resource, three images and three items. With all `crash-tools`
methods disabled, `unzip -t plugins/CraftEngine/generated/resource_pack.zip` must report no
errors. The archive must contain the three `assets/escapefromcraftov/items/*.json` models,
`assets/escapefromcraftov/font/hud.json`, `assets/escapefromcraftov/font/gui.json`, and their PNG
textures.

## Carry GUI artwork

`art/carry_gui.svg` is the editable 176x222 master for a 9x6 chest/title glyph. The registered
`escapefromcraftov:carry_gui` image uses `U+E020` in `escapefromcraftov:gui`; render the menu title
with that font and glyph as
`<white><shift:-8><image:escapefromcraftov:carry_gui>`, applying the title's eight-pixel correction
so the artwork begins at the vanilla container's left edge.

The standard starter projection is drawn as a graphite shell with olive backpack and pocket
frames, a cyan chest-rig frame, an amber equipment bar, four individually numbered pocket cells,
and dedicated secure/status/close controls. Every 16x16 item interior in the 9x6 container,
three-row player inventory and hotbar is alpha-punched, so the title artwork cannot cover item
icons even if a client renders title glyphs late. Keep menu item coordinates on the vanilla
8+18n / 18+18n grid when changing the projection.

## Artwork provenance

The committed SVG/PNG artwork is original project artwork, designed as a dark graphite/olive
tactical interface with restrained cyan accents. An attempted `gpt-image-2` generation could not
run because the local `OPENAI_API_KEY` was not a valid OpenAI API key, so no generated image was
represented as an AI output. The deterministic SVG sources under `art/` are the editable masters;
`art/item-preview.png` is a contact sheet for visual review.
