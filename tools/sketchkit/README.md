# Sketch kit

Draws the pictures in Settings and the introduction (`res/drawable/sketch_*`,
`intro_*`) from the app's own icons and Roboto lettering. Change a picture
here and run it again; don't edit the drawables by hand.

Once:

    npm install opentype.js@1.3.4
    unzip -o ../../app/libs/lib-ui-release.aar 'res/*' -d .libui
    # Roboto 400 and 500 as roboto-400.ttf and roboto-500.ttf (Google Fonts)

Then:

    node pictures.js ../../app/src/main/res/drawable out.json

`out.json` holds the same pictures as SVG, for checking them in a browser.
