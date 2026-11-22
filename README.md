# EclispeOS-tv-marketplace
Official Marketplace repo for EclipseOS (TV)

## Catalog and artwork

`repository-v2.json` is the canonical catalog. Each entry identifies a runtime
`bundles/<id>/manifest.json`; its optional `version` enables official in-place
updates without resetting tile preferences. The legacy `repository.json` and
old bundle IDs remain available.

Eclipse 1.1 resolves the catalog revision through GitHub's small
`git/ref/heads/main` response, not full commit details and file patches.
Large artwork/source commits can exceed older launchers' commit-response
limit and produce "A bundle file is too large" during refresh; update the
Eclipse host APK rather than increasing runtime asset limits. Catalogs remain
limited to 32 KiB and downloads stay pinned to the resolved commit.

Runtime artwork is transparent PNG, at most 512 x 512 pixels and 256 KiB.
Manifests are at most 32 KiB. Original supplied artwork is kept as `artwork.svg`.
The separate `marketplace/marketplace-manifest.json` contains listing details;
runtime installation does not download the marketplace directory.
The profiler generates a `capabilityReport` from the merged, current-firmware
facts and preserves other listing metadata. Eclipse displays the report by
category in the profiler's format, including unknowns and verification caveats.
Optional hand-authored `capabilitySections` (`title` and `items`) and older
`capabilities` arrays remain supported when no generated report is present.
Keep listing descriptions consistent with their runtime compatibility profile.

## Brand icons

`brands/index.json` lists brand directory IDs. Each `brands/<id>/manifest.json`
defines its `title`, relative PNG `icon` path, named `colours` and associated
Android `packages`. Keep the index in sync when adding or removing brands.
`artwork.xml` or `artwork.svg` preserves the original vector; the app downloads
the PNG.
The icon picker refreshes these manifests at a pinned Marketplace revision.
Brand metadata and used icons are cached locally for offline tiles; other
icons are downloaded only for browsing and kept in memory.

Regenerate the supplied app/input/plugin artwork with Node.js and ImageMagick:

```sh
node tools/update-icons.mjs /path/to/Logos /path/to/Eclipse /path/to/compatibility-profiler.svg
node tools/sync-native-bundles.mjs /path/to/Eclipse
```

The converter preserves aspect ratios and bumps existing artwork versions only
when their image changes. Native Music, Movies, TV Shows, Jellyfin, Navidrome
and Compatibility Profiler require Eclipse 1.1 (version code 2) or later.
Their bundled Kotlin sources are informational/build inputs, not executable
downloads; installing a catalog entry cannot upgrade the Eclipse host APK.

Uninstalled plugins are offered only in Marketplace. Settings lists connection
plugins only after installation; media-app listing manifests do not advertise
optional plugins.

Music offers per-user List/five-column Grid choices for Artists, Albums and Playlists;
Songs stay in a list. Open collections use a left artwork/information pane and
a right track list, with parent-only Back navigation. Artists use plural credits
so shared tracks appear under each contributor. Select an artist to browse
their albums, then an album to open its full track list; Back restores each
parent selection. Album details use square artwork, and artist images are
circular in lists, grids and details, using the artist's own primary image or
an artist placeholder, never an album-cover substitute. Album, movie and TV-show artwork matches
the app tiles' rounded corners. TV Shows uses a five-column portrait-poster
grid; opening a show retains its episode list and parent selection.
Album track lists show track numbers and per-song contributing artists;
Songs and playlist rows retain their icons and artist/album subtitles.

Playing Music supplies artist-image live thumbnails with Ken Burns motion and
crossfading photographs, falling back to album artwork. The same timeline
continues into the app's 25%-opacity backdrop behind its focus glow. Artwork
fades in on playback start and crossfades over one second when it changes,
keeping the previous image visible during buffering and image loading. Home's Live
Thumbnail switch is independent of the app backdrop. Images are bounded and
public artwork hosts never receive media-server credentials.
All three media apps smoothly scroll lists and grids. After five idle seconds
with playback controls focused, the UI and clock fade away until remote or touch
input, leaving the backdrop visible. Browsing does not auto-hide.
Music only hides during active playback: pausing, stopping or loading the next
track immediately restores its UI and clock. The first remote press or touch
gesture that wakes Music is consumed, including its release and held repeats.
The footer title/artist is a Queue tab. Open it to see the current track at the
top and Up next below; scroll up for previously played entries in the current
in-memory queue. Selecting an entry preserves its existing queue/shuffle order,
and Back restores the previous browser page and selection.
Playback controls are one Left/Select/Right row for Previous/Play/Next; Music
also has a grouped Shuffle/Favourite/Repeat row. Side presses move the focus
glow temporarily, returning it to the centre on release.
Media apps synchronize their Home handoffs with presented UI frames, fade their
interfaces over the continuous backdrop and defer tile rebuilding until the
return animation finishes. The fullscreen video player and decoder handoff
remain unchanged.
Music includes an icon-only heart between Shuffle and Repeat, cycling unset,
favourite and dislike. Ratings are local to Eclipse, per song and user; they
do not alter server ratings or automatically skip tracks.

## Model compatibility

Official TV plugins match Android model, product and SDK exactly and install
automatically on matching Eclipse devices, unless explicitly removed.
`compatibility.capabilities` stores `display`, `sound`, `os`, `remote`, `buffers`,
`verification` and `recommendations`. Firmware-dependent facts are scoped with
`os.firmwareBuild`; optional `firmwareProfiles` preserves earlier snapshots.
Plugins select whitelisted native backends, never arbitrary shell commands.

The JVC G24 profile records tested firmware and the distinction between panel
support and actual compositor output: 4K buffers were composed into a 1080p
graphics target at about 19 fps. Its recommended UI buffers are therefore
1080p SDR. Dolby Vision/HDR10/HLG panel support does not establish HDR UI
support. Audio-policy declarations are not playback guarantees for connected
receivers; unknown decoder, channel and scaler limits remain unknown.

## Compatibility Profiler

**Compatibility Profiler**, by **Eclipse**, uses Settings' Graphite colour
(`#4A505C`) and the supplied profiler icon. Install it through Marketplace,
authorize Eclipse's existing local ADB connection, and scan/review Display,
Sound, OS, Remote and Buffers. All probes are read-only and command-allowlisted.
Use D-pad directions to navigate both panes, Select to activate a row, and
Back to close a dialog or return to its parent report page. Root pages have no
Back button and stay in the app; use Home to return to the dashboard.
Display modes and codec limits are labelled as advertised; measured UI cadence
is reported only when the profiler's own surface exposes usable timing.

Contribute uses locally generated QR codes and GitHub Device Flow. The public
OAuth Client ID is configuration, not a secret; Device Flow must be enabled.
The token stays in RAM and is cleared on cancellation, sign-out or app exit.
No report is published until the user separately chooses **Create public pull
request**. This creates a fork if needed, a compatibility branch and a PR,
never a direct change to main. Retrying the same report reuses its branch/PR.
Reports exclude raw dumps, serials, network addresses, SSIDs and credentials.
Model, firmware and capability facts will be public in the proposed PR.

Review contributions before merging: API-reported codec limits, connected-route
audio formats, physical panel HDR and key presence do not prove simultaneous
decode, bitstream playback, HDR UI output or remote event delivery. Existing
same-firmware verified recommendations survive new scans.
