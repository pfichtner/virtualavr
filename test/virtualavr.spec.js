const { runCode } = require('../virtualavr');
const waitForExpect = require("wait-for-expect");

jest.setTimeout(10000);

// Tests:
// Can load source/compile
// Can load zip/compile
// Can load hex
// - Callback called
// - WS messages sent: 
//   - pin states
//   - TX/RX
//   - debug-messages?
// - WS messages read 
// - Does run loop/demo sketch without params?
// - Support for running without serial device? -> Add test for it
// - Switch of pinMode (analog/digital)


describe('My Test Suite', () => {
	it('My Test Case', async () => {
		const mockFunction = jest.fn()
		runCode('./code.ino', mockFunction);
		await waitForExpect(() => {
			expect(mockFunction).toHaveBeenCalledWith(13, '1');
			expect(mockFunction).toHaveBeenCalledWith(13, '0');
		});
	});	
});

