# Changelog

## [1.2.4] - 2025-10-16

### Changed
- Seller/buyer tables now compute owner and waystone column widths up-front, trimming longest entries together to avoid chat wrapping.
- Owner and waystone names show ellipsis with hover tooltips when truncated; waypoint links reuse the same column logic.

## [1.2.3] - 2025-10-07

### Added
- Optional (unbound) keybind for quick `/pb` lookups, configurable in the Controls menu.

### Changed
- History panel now highlights latest entry inline, shows directional arrows without numeric deltas, and better aligns rows.
- History highs and lows only appear when prices actually vary.

## [1.2.2] - 2025-10-06

### Added
- Mod Menu settings screen with an enable/disable toggle for Pricebook.

### Changed
- Configuration now persists an `enabled` flag and refreshes the session when it changes.
- `/pb` without arguments now prefers the item on the shop sign you're targeting before falling back to the held item.

## [1.2.1] - 2025-10-05

### Changed
- Refactor: Separate PricebookRenderer from PricebookCommand

## [1.2.0] - 2025-10-04

### Added
- Price history feature with `/pricebook_history` command to view historical price data
- Clickable "[View Price History]" link in pricebook output
- Dynamic price formatting that shows decimals only when needed (whole numbers display without decimals)

### Changed
- Improved UI with cleaner box-drawing characters (┌, │) for better visual structure

## [1.1.0] - 2025-10-03

### Changed
- Improved search functionality with multi-word token matching - type "bamboo block" to find "block of bamboo"
- All item search suggestions now display in lowercase
- Removed username from sender ID generation for improved privacy


## [1.0.0] - 2025-09-26

Initial Release
