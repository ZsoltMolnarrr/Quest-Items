# Enchant Limiter

Limit the number of enchantments on an item.

Automatically applies limit to all breakable items and enchanted books by default.

## Item component

Enchantment limit is based on a new item component: `enchant_limiter:limit` 

Example command
```
/give @p minecraft:golden_sword[enchant_limiter:limit={"count":3}]
```

## Configuration

- Adjustable default enchantment limit (default: 3)

