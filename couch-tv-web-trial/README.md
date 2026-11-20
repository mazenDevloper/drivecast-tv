# couch-tv web trial (option A, in the browser)

A trimmed copy of the TV home from https://github.com/Seagey/couch-tv (MIT), with the mock mode
so you can try option A with no TV and no bridge.

    python3 -m http.server 8000
    # open http://localhost:8000/tv-home/web/index.html?mock=1

Arrow keys + Enter act as the TV remote. Presets go in the address, for example:

    ?mock=1&look=night&glass=on&icons=glass&dock=mac&clockstyle=liquid&amount=4&lang=ar

All presets are listed at the top of tv-home/web/mock.js; their meaning is in docs/CONFIG.md.
