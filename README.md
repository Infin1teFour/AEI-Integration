# AEI-Integration

Mod for exporting recipes to be used in [AEI](https://github.com/Infin1teFour/AEI)

/aei export_all <image_resolution> should be all you need. The rest are mostly for debug.

## AE2 Grid API

Applied Energistics 2 (`ae2`) is required. You can start a local HTTP API to inspect nearby ME grid inventory.

Commands:

- `/aei ae2_api start` (default port `8787`)
- `/aei ae2_api start <port>`
- `/aei ae2_api status`
- `/aei ae2_api stop`

Notes:

- Current implementation is integrated-server (singleplayer) oriented.
- The API binds to `127.0.0.1` only.
- Discovery scans loaded block entities and includes only grids that have at least one Wireless Access Point.

Endpoints:

- `GET /health`
- `GET /api/ae2/grid`

Sample response shape from `/api/ae2/grid`:

```json
{
	"source": "AEI",
	"discovery": "wap_scan",
	"gridCount": 1,
	"grids": [
		{
			"index": 0,
			"nodeCount": 47,
			"stackTypes": 3,
			"totalAmount": 14512,
			"stacks": [
				{
					"id": "minecraft:iron_ingot",
					"type": "ae2:item",
					"name": "Iron Ingot",
					"amount": 1024
				}
			]
		}
	]
}
```

## Export format contract

All exports are written under `run/AEIExport` when launched from the dev client.

### `translations.json`

Pretty-printed JSON object mapping exported asset keys to display names.

Item entries use the item id as the key:

```json
{
	"minecraft:acacia_boat": "Acacia Boat"
}
```

Non-item JEI ingredient entries use `<ingredientTypeUid>/<namespace:path>` as the key:

```json
{
	"forge:fluid_stack/minecraft:water": "Water"
}
```

Downstream rule: treat keys as opaque identifiers. Do not assume every key is an item id.

### `inventory_images/`

PNG image directory for exported ingredients.

Item images are written at the directory root using:

- `<namespace>_<path>.png`

Example:

- `inventory_images/minecraft_acacia_boat.png`

Non-item JEI ingredient images are written under a subdirectory derived from the JEI ingredient type uid, where `:` and `/` are replaced with `_`:

- `<sanitizedTypeUid>/<namespace>_<path>.png`

Example:

- `inventory_images/forge_fluid_stack/minecraft_water.png`

Image properties:

- PNG with transparency
- square dimensions equal to the requested export size
- background black pixels are stripped to transparent during post-processing

### `recipes.json`

Pretty-printed JSON array. Each element is one exported JEI recipe.

Schema:

```json
[
	{
		"recipeType": "minecraft:crafting",
		"recipeClass": "net.minecraft.world.item.crafting.ShapelessRecipe",
		"slots": [
			{
				"role": "OUTPUT",
				"ingredients": [
					{
						"type": "item",
						"id": "minecraft:yellow_dye",
						"count": 1
					}
				]
			},
			{
				"role": "INPUT",
				"ingredients": []
			}
		]
	}
]
```

Ingredient object variants:

- Item: `{ "type": "item", "id": "namespace:path", "count": number, "nbt": "{...}"? }`
- Fluid: `{ "type": "fluid", "id": "namespace:path", "amount": number }`
- Generic JEI ingredient with resolvable registry key: `{ "type": "<ingredientTypeUid>", "id": "namespace:path", "amount": number? }`
- Fallback generic ingredient: `{ "type": "<ingredientTypeUid>", "raw": "toString() value" }`

Downstream rules:

- `slots[].ingredients` may be empty.
- `role` is the JEI slot role enum name.
- Unknown ingredient `type` values should be preserved and handled as modded ingredient types, not rejected.

### `recipes_by_type/*.ndjson`

Per-recipe-type chunk files. Each line is one JSON recipe object using the exact same schema as an element in `recipes.json`.

Filename format:

- `<sanitizedRecipeTypeUid>.ndjson`

Sanitization rule:

- keep ASCII letters, digits, `_`, and `-`
- replace every other character with `_`

Example:

- `minecraft_crafting.ndjson`

### `recipes_manifest.json`

Manifest describing the chunked recipe export.

Schema:

```json
{
	"formatVersion": 1,
	"legacyFile": "recipes.json",
	"chunkDirectory": "recipes_by_type",
	"totals": {
		"recipeTypes": 1,
		"recipes": 2,
		"slots": 20,
		"ingredients": 4
	},
	"types": [
		{
			"recipeType": "minecraft:crafting",
			"file": "recipes_by_type/minecraft_crafting.ndjson",
			"recipes": 2,
			"slots": 20,
			"ingredients": 4
		}
	]
}
```

Downstream rule: prefer `recipes_manifest.json` plus `recipes_by_type/*.ndjson` for scalable loading; use `recipes.json` as the compatibility aggregate.

### `*.aei`

Packaged export archive. This is a standard ZIP file with an `.aei` extension.

Archive contents:

- `translations.json`
- `recipes.json`
- `recipes_manifest.json` if present
- `recipes_by_type/**` if present
- `assets/inventory_images/**`

Downstream rule: the `.aei` extension is only a packaging convention. Any ZIP reader can open it.

## Command outputs

- `/aei export_block_items [size]` writes `translations.json` and `inventory_images/`
- `/aei export_recipes` writes `recipes.json`, `recipes_manifest.json`, and `recipes_by_type/`
- `/aei export_all [size]` writes all of the above and then builds an `.aei` archive
- `/aei build_package` packages existing exported files into an `.aei` archive
