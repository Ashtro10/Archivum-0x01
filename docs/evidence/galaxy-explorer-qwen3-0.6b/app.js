window.CluApp = {
    mount: async function (kit) {
        const scene = await CluScene3D.create(document.getElementById('scene'), {
            background: "#02040a",
            onPick: function (node) {
                // Logic for picking a node
            }
        });

        // Initialize scene nodes
        const nodes = [
            { id: 'root', label: 'Root', x: -200, y: 100, z: 0, size: 30, color: '#f0f0f0' },
            { id: 'alpha', label: 'Alpha', x: 200, y: 50, z: 0, size: 20, color: '#00ff00' },
            { id: 'beta', label: 'Beta', x: 100, y: 150, z: 0, size: 10, color: '#0000ff' }
        ];

        scene.setNodes(nodes);

        // Set initial path
        const currentFolder = await kit.fs.list('');
        if (currentFolder) {
            const pathElement = document.getElementById('path');
            pathElement.textContent = `Current Folder: ${currentFolder.path}`;
        }

        // Update status
        kit.status('Ready');

        // Handle errors
        kit.status('Error: Permission denied');

        // Handle file reading
        kit.status('File read: ' + currentFolder.text);

        // Update message
        kit.status('Message: ' + currentFolder.message);

        // Update scene nodes
        scene.updateNodes(nodes);
    }
};

window.CluApp = {
    mount: async function (kit) {
        const scene = await CluScene3D.create(document.getElementById('scene'), {
            background: "#02040a",
            onPick: function (node) {
                // Logic for picking a node
            }
        });

        // Initialize scene nodes
        const nodes = [
            { id: 'root', label: 'Root', x: -200, y: 100, z: 0, size: 30, color: '#f0f0f0' },
            { id: 'alpha', label: 'Alpha', x: 200, y: 50, z: 0, size: 20, color: '#00ff00' },
            { id: 'beta', label: 'Beta', x: 100, y: 150, z: 0, size: 10, color: '#0000ff' }
        ];

        scene.setNodes(nodes);

        // Set initial path
        const currentFolder = await kit.fs.list('');
        if (currentFolder) {
            const pathElement = document.getElementById('path');
            pathElement.textContent = `Current Folder: ${currentFolder.path}`;
        }

        // Update status
        kit.status('Ready');

        // Handle errors
        kit.status('Error: Permission denied');

        // Handle file reading
        kit.status('File read: ' + currentFolder.text);

        // Update message
        kit.status('Message: ' + currentFolder.message);

        // Update scene nodes
        scene.updateNodes(nodes);
    }
};
