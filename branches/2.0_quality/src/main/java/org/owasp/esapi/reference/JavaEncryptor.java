/**
 * OWASP Enterprise Security API (ESAPI)
 * 
 * This file is part of the Open Web Application Security Project (OWASP)
 * Enterprise Security API (ESAPI) project. For details, please see
 * <a href="http://www.owasp.org/index.php/ESAPI">http://www.owasp.org/index.php/ESAPI</a>.
 *
 * Copyright (c) 2007 - The OWASP Foundation
 * 
 * The ESAPI is published by OWASP under the BSD license. You should read and accept the
 * LICENSE before you use, modify, and/or redistribute this software.
 * 
 * @author Jeff Williams <a href="http://www.aspectsecurity.com">Aspect Security</a>
 * @created 2007
 */
package org.owasp.esapi.reference;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;

import org.owasp.esapi.CipherText;
import org.owasp.esapi.ESAPI;
import org.owasp.esapi.Logger;
import org.owasp.esapi.codecs.Hex;
import org.owasp.esapi.errors.ConfigurationException;
import org.owasp.esapi.errors.EncryptionException;
import org.owasp.esapi.errors.IntegrityException;
import org.owasp.esapi.util.CipherSpec;
import org.owasp.esapi.util.CryptoHelper;

/**
 * Reference implementation of the Encryptor interface. This implementation
 * layers on the JCE provided cryptographic package. Algorithms used are
 * configurable in the ESAPI.properties file.
 * 
 * @author Jeff Williams (jeff.williams .at. aspectsecurity.com) <a
 *         href="http://www.aspectsecurity.com">Aspect Security</a>
 * @author kevin.w.wall@gmail.com
 * @since June 1, 2007; some methods since ESAPI Java 2.0
 * @see org.owasp.esapi.Encryptor
 */
public class JavaEncryptor implements org.owasp.esapi.Encryptor {

    // encryption
    private static SecretKeySpec secretKeySpec = null; // Why static? Implies one key?!?
    private static String encryptAlgorithm = "AES";
    private static String encoding = "UTF-8"; 
    private static int encryptionKeyLength = 256;
    
    // digital signatures
    private static PrivateKey privateKey = null;
	private static PublicKey publicKey = null;
	private static String signatureAlgorithm = "SHAwithDSA";
    private static String randomAlgorithm = "SHA1PRNG";
	private static int signatureKeyLength = 1024;
	
	// hashing
	private static String hashAlgorithm = "SHA-512";
	private static int hashIterations = 1024;
	
	// Logging - DISCUSS: This "sticks" us with a specific logger to whatever it was when
	//					  this class is loaded. Is that a big limitation?
	private static Logger logger = ESAPI.getLogger("JavaEncryptor");
	
    /**
     * Generates a new strongly random secret key and salt that can be used in the ESAPI properties file.
     * 
     * @param args Set first argument to "-print" to display available algorithms on standard output.
     * @throws java.lang.Exception	To cover a multitude of sins, mostly in configuring ESAPI.properties.
     */
    public static void main( String[] args ) throws Exception {
		System.out.println( "Generating a new secret master key" );
		System.out.println( "   use -print to show available crypto algorithms" );
		
		// print out available ciphers
		if ( args.length == 1 && args[0].equalsIgnoreCase("-print" ) ) {
			System.out.println( "AVAILABLE ALGORITHMS" );
					
			Provider[] providers = Security.getProviders();
			TreeMap<String, String> tm = new TreeMap<String, String>();
			// DISCUSS: Note: We go through multiple providers, yet nowhere do I
			//			see where we print out the provider name. Not all providers
			//			will implement the same algorithms and some "partner" with
			//			whom we are exchanging different cryptographic messages may
			//			have _different_ providers in their java.security file. So
			//			it would be useful to know the provider name where each
			//			algorithm is implemented. Might be good to prepend the provider
			//			name to the 'key' with something like "providerName: ". Thoughts?
			for (int i = 0; i != providers.length; i++) {
				Iterator it = providers[i].keySet().iterator();
				while (it.hasNext()) {
		            String key = (String) it.next();
		            String value = providers[i].getProperty( key );
		            tm.put(key, value);
				}
			}
			Iterator it = tm.entrySet().iterator();
			while( it.hasNext() ) {
				Map.Entry entry = (Map.Entry)it.next();
				String key = (String)entry.getKey();
				String value = (String)entry.getValue();
	        	System.out.println( "   " + key + " -> "+ value );
			}
		}
		
        // setup algorithms
        encryptAlgorithm = ESAPI.securityConfiguration().getEncryptionAlgorithm();
		encryptionKeyLength = ESAPI.securityConfiguration().getEncryptionKeyLength();
		randomAlgorithm = ESAPI.securityConfiguration().getRandomAlgorithm();

        KeyGenerator kgen = KeyGenerator.getInstance( encryptAlgorithm );
		SecureRandom random = SecureRandom.getInstance(randomAlgorithm);
        kgen.init( encryptionKeyLength, random );
        SecretKey secretKey = kgen.generateKey();        
        byte[] raw = secretKey.getEncoded();
        byte[] salt = new byte[20];	// Or 160-bits
        random.nextBytes( salt );
        String eol = System.getProperty("line.separator", "\n"); // So it works on Windows too.
        System.out.println( eol + "Copy and paste these lines into ESAPI.properties" + eol);
        System.out.println( "#==============================================================");
        System.out.println( "Encryptor.MasterKey=" + ESAPI.encoder().encodeForBase64(raw, false) );
        System.out.println( "Encryptor.MasterSalt=" + ESAPI.encoder().encodeForBase64(salt, false) );
        System.out.println( "#==============================================================" + eol);
    }
	
    
    /**
     * CTOR for {@code JavaEncryptor}.
     * @throws EncryptionException if can't construct this object for some reason.
     * 					Original exception will be attached as the 'cause'.
     */
    public JavaEncryptor() throws EncryptionException {
		byte[] salt = ESAPI.securityConfiguration().getMasterSalt();
		byte[] skey = ESAPI.securityConfiguration().getMasterKey();

		// setup algorithms
        encryptAlgorithm = ESAPI.securityConfiguration().getEncryptionAlgorithm();
		signatureAlgorithm = ESAPI.securityConfiguration().getDigitalSignatureAlgorithm();
		randomAlgorithm = ESAPI.securityConfiguration().getRandomAlgorithm();
		hashAlgorithm = ESAPI.securityConfiguration().getHashAlgorithm();
		hashIterations = ESAPI.securityConfiguration().getHashIterations();
		encoding = ESAPI.securityConfiguration().getCharacterEncoding();
		encryptionKeyLength = ESAPI.securityConfiguration().getEncryptionKeyLength();
        signatureKeyLength = ESAPI.securityConfiguration().getDigitalSignatureKeyLength();
        
		try {
            // Set up encryption and decryption
            secretKeySpec = new SecretKeySpec(skey, encryptAlgorithm );

			// Set up signing keypair using the master password and salt
			KeyPairGenerator keyGen = KeyPairGenerator.getInstance(signatureAlgorithm);
			SecureRandom random = SecureRandom.getInstance(randomAlgorithm);
			byte[] seed = hash(new String(skey, encoding),new String(salt, encoding)).getBytes(encoding);
			random.setSeed(seed);
			keyGen.initialize(signatureKeyLength, random);
			KeyPair pair = keyGen.generateKeyPair();
			privateKey = pair.getPrivate();
			publicKey = pair.getPublic();
		} catch (Exception e) {
			// can't throw this exception in initializer, but this will log it
			// CHECKME: Huh? This is not in a _static_ initializer. Just declare
			//          this CTOR with a 'throws EncryptionException'. In fact, it's
			//			WRONG to NOT throw here otherwise you are returning an improperly
			//			constructed object that most assuredly will cause problems somewhere
			//			downstream. And those problems will be much harder to troubleshoot.
			//		    Besides, even if it were used in a static initializer it's better
			//			to throw here and use a try / catch in the static initializer.
			throw	// Added!!!
				new EncryptionException("Encryption failure", "Error creating Encryptor", e);
		}
	}

	/**
     * {@inheritDoc}
     * 
	 * Hashes the data with the supplied salt and the number of iterations specified in
	 * the ESAPI SecurityConfiguration.
	 */
	public String hash(String plaintext, String salt) throws EncryptionException {
		return hash( plaintext, salt, hashIterations );
	}
	
	/**
     * {@inheritDoc}
     * 
	 * Hashes the data using the specified algorithm and the Java MessageDigest class. This method
	 * first adds the salt, a separator (":"), and the data, and then rehashes the specified number of iterations
	 * in order to help strengthen weak passwords.
	 */
	public String hash(String plaintext, String salt, int iterations) throws EncryptionException {
		byte[] bytes = null;
		try {
			MessageDigest digest = MessageDigest.getInstance(hashAlgorithm);
			digest.reset();
			digest.update(ESAPI.securityConfiguration().getMasterSalt());
			digest.update(salt.getBytes(encoding));
			digest.update(plaintext.getBytes(encoding));

			// rehash a number of times to help strengthen weak passwords
			bytes = digest.digest();
			for (int i = 0; i < iterations; i++) {
				digest.reset();
				bytes = digest.digest(bytes);
			}
			String encoded = ESAPI.encoder().encodeForBase64(bytes,false);
			return encoded;
		} catch (NoSuchAlgorithmException e) {
			throw new EncryptionException("Internal error", "Can't find hash algorithm " + hashAlgorithm, e);
		} catch (UnsupportedEncodingException ex) {
			throw new EncryptionException("Internal error", "Can't find encoding for " + encoding, ex);
		}
	}
	
	/**
	* {@inheritDoc}
	*/
	@Deprecated public String encrypt(String plaintext) throws EncryptionException {
		// Note - Cipher is not thread-safe so we create one locally
		try {
			Cipher encrypter = Cipher.getInstance(encryptAlgorithm);
			encrypter.init(Cipher.ENCRYPT_MODE, secretKeySpec);
			byte[] output = plaintext.getBytes(encoding);
			byte[] enc = encrypter.doFinal(output);
			return ESAPI.encoder().encodeForBase64(enc,false);
		} catch (InvalidKeyException ike) {
			throw new EncryptionException("Encryption failure", "Must install unlimited strength crypto extension from Sun", ike);
		} catch (Exception e) {
			throw new EncryptionException("Encryption failure", "Encryption problem: " + e.getMessage(), e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	 public CipherText encrypt(byte[] plaintext) throws EncryptionException {
		 // Now more of a convenience function for using the master key.
		 return encrypt(secretKeySpec, plaintext, false, false);
	 }
	 
	 /**
	  * TODO: Javadoc
	  */
	 public CipherText encrypt(SecretKey key, byte[] plaintext, boolean overwriteKey, boolean overwritePlaintext)
	 						throws EncryptionException
	 {
		 boolean success = false;	// Used in 'finally' clause.
		 try {
			 assert key != null : "Encryption key may not be null";

			 String xform = ESAPI.securityConfiguration().getCipherTransformation();
			 	// Note - Cipher is not thread-safe so we create one locally
			 Cipher encrypter = Cipher.getInstance(xform);
			 int keyLen = ESAPI.securityConfiguration().getEncryptionKeyLength();
			 int keySize = key.getEncoded().length * 8;
			 
			 // DISCUSS: OK, what do we want to do here if keyLen != keySize? If use keyLen, encryption
			 //		     will fail with an exception, but perhaps that's what we want. Or we may just be
			 //			 OK with silently using keySize as long as keySize >= keyLen, which then interprets
			 //			 ESAPI.EncryptionKeyLength as the *minimum* key size, but as long as we have something
			 //			 stronger it's OK to use it. For now, I am just going to log warning if different, but use
			 //			 keySize unless keySize is SMALLER than keyLen, in which case I'm going to throw.
			 //
			 if ( keySize != keyLen ) {
				 logger.warning(Logger.SECURITY_FAILURE, "Specified encryption key length (ESAPI.EncryptionKeyLength) is " +
						 			keyLen + "bits, but length of actual encryption is " + keySize + " bits.");
			 }
			 if ( keySize < keyLen ) {
				 throw new ConfigurationException("Actual key size of " + keySize + " bits smaller than specified " +
						 						  "encryption key length (ESAPI.EncryptionKeyLength) of " + keyLen + " bits.");
			 }
			 byte[] ivBytes = null;
			 CipherSpec cipherSpec = new CipherSpec(encrypter, keySize);
			 if ( cipherSpec.requiresIV() ) {
				 String ivType = ESAPI.securityConfiguration().getIVType();
				 IvParameterSpec ivSpec = null;
				 if ( ivType.equalsIgnoreCase("random") ) {
					 ivBytes = ESAPI.randomizer().getRandomBytes(encrypter.getBlockSize());
				 } else if ( ivType.equalsIgnoreCase("fixed") ) {
					 String fixedIVAsHex = ESAPI.securityConfiguration().getFixedIV();
					 ivBytes = Hex.decode(fixedIVAsHex);
/* FUTURE		 } else if ( ivType.equalsIgnoreCase("specified")) {
					 // FUTURE - TODO  - Create instance of specified class to use for IV generation and
					 //					 use it to create the ivBytes. (The intent is to make sure that
					 //				     1) IVs are not repeated for cipher modes like OFB and CFB, and
					 //					 2) to screen for weak IVs for the particular cipher algorithm.
 */
				 } else {
					 // TODO: Update to add 'specified' once that is supported.
					 throw new ConfigurationException("Property Encryptor.ChooseIVMethod must be set to 'random' or 'fixed'");
				 }
				 ivSpec = new IvParameterSpec(ivBytes);
				 cipherSpec.setIV(ivBytes);
				 encrypter.init(Cipher.ENCRYPT_MODE, key, ivSpec);
			 } else {
				 encrypter.init(Cipher.ENCRYPT_MODE, key);
			 }			 
			 byte[] raw = encrypter.doFinal(plaintext);
			 CipherText ciphertext = new DefaultCipherText(cipherSpec, raw);
			 ciphertext.computeAndStoreMIC(key.getEncoded());
			 success = true;	// W00t!!!
			 return ciphertext;
		 } catch (InvalidKeyException ike) {
			 throw new EncryptionException("Encryption failure", "Must install unlimited strength crypto extension from Sun", ike);
		 } catch (ConfigurationException cex) {
			 throw new EncryptionException("Encryption failure", "Check encryption keys vs. ESAPI.EncryptionKeyLength", cex);
		 } catch (Exception e) {
			 throw new EncryptionException("Encryption failure", "Encryption problem: " + e.getMessage(), e);
		 } finally {
			 // Don't overwrite anything in the case of exceptions because they may wish to retry.
			 if ( success && overwriteKey ) {
				 // CHECKME: Not sure this will work. Need to test. SecretKey.getEncoded() might return a copy.
				 CryptoHelper.overwrite( key.getEncoded() );
			 }
			 if ( success && overwritePlaintext ) {
				 CryptoHelper.overwrite(plaintext);
			 }
		 }
	}

	/**
	* {@inheritDoc}
	*/
	@Deprecated public String decrypt(String ciphertext) throws EncryptionException {
		// Note - Cipher is not thread-safe so we create one locally
		try {
			Cipher decrypter = Cipher.getInstance(encryptAlgorithm);
			decrypter.init(Cipher.DECRYPT_MODE, secretKeySpec);
			byte[] dec = ESAPI.encoder().decodeFromBase64(ciphertext);
			byte[] output = decrypter.doFinal(dec);
			return new String(output, encoding);
		} catch (InvalidKeyException ike) {
			throw new EncryptionException("Decryption failure", "Must install unlimited strength crypto extension from Sun", ike);
		} catch (NoSuchAlgorithmException e) {
			throw new EncryptionException("Decryption failed", "Decryption problem - unknown algorithm: " + e.getMessage(), e);
		} catch (NoSuchPaddingException e) {
			// Shouldn't happen; this deprecated method does not use padding.
			throw new EncryptionException("Decryption failed", "Decryption problem - unknown padding: " + e.getMessage(), e);
		} catch (IOException e) {
			throw new EncryptionException("Decryption failed", "Decryption problem: " + e.getMessage(), e);
		} catch (IllegalBlockSizeException e) {
			throw new EncryptionException("Decryption failed", "Decryption problem - invalid block size: " + e.getMessage(), e);
		} catch (BadPaddingException e) {
			// This could happen though, BECAUSE padding is not used.
			throw new EncryptionException("Decryption failed", "Decryption padding problem: " + e.getMessage(), e);
		}
/*		
	 	Shame on you! Don't you read your own advisories! ;-)
	 			See		http://www.owasp.org/index.php/Overly-Broad_Catch_Block
		} catch (Exception e) {
			throw new EncryptionException("Decryption failed", "Decryption problem: " + e.getMessage(), e);
		}
*/

	}

	/**
	 * {@inheritDoc}
	 */
	public byte[] decrypt(CipherText ciphertext) throws EncryptionException {
		 // Now more of a convenience function for using the master key.
		 return decrypt(secretKeySpec, ciphertext);
	}

	/**
	 * {@inheritDoc}
	 */
	public byte[] decrypt(SecretKey key, CipherText ciphertext) throws EncryptionException
	{
		try {
			assert key != null : "Encryption key may not be null";
			assert ciphertext != null : "Ciphertext may not be null";
			
			Cipher decrypter = Cipher.getInstance(ciphertext.getCipherTransformation());
			if ( ciphertext.requiresIV() ) {
				decrypter.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ciphertext.getIV()));
			} else {
				decrypter.init(Cipher.DECRYPT_MODE, key);
			}
			byte[] output = decrypter.doFinal(ciphertext.getRawCipherText());
				// The decryption was "successful", but there are rare instances (approximately
				// 1 in a 1000) where the wrong key or IV was used but the ciphertext still
				// decrypts correctly, but simply results in garbage. (The other 999 times out
				// of 1000 it will fail with a BadPaddingException [assuming PKCS#5 padding].)
				// Thus at this point, we check (optionally) validate the MIC. If it returns
				// false, rather than returning the (presumably) garbage plaintext, we return
				// throw an exception.
			boolean success = ciphertext.validateMIC(ciphertext.getRawCipherText());
			if ( !success ) {
					// Stop the debugger here if you don't believe us.
				throw new EncryptionException("Decryption verification failed.",
									"Decryption returned without throwing but MIC verification  " +
						          	"failed, meaning returned plaintext was garbarge");
			}
			return output;
		} catch (InvalidKeyException ike) {
			throw new EncryptionException("Decryption failure", "Must install unlimited strength crypto extension from Sun", ike);
		} catch (NoSuchAlgorithmException e) {
			throw new EncryptionException("Decryption failed", "Invalid algorithm for available JCE providers - " +
						ciphertext.getCipherTransformation() + ": " + e.getMessage(), e);
		} catch (NoSuchPaddingException e) {
			throw new EncryptionException("Decryption failed", "Invalid padding scheme (" +
						ciphertext.getPaddingScheme() + ") for cipher transformation " + ciphertext.getCipherTransformation() +
						": " + e.getMessage(), e);
		} catch (InvalidAlgorithmParameterException e) {
			throw new EncryptionException("Decryption failed", "Decryption problem: " + e.getMessage(), e);
		} catch (IllegalBlockSizeException e) {
			throw new EncryptionException("Decryption failed", "Decryption problem: " + e.getMessage(), e);
		} catch (BadPaddingException e) {
			boolean success = ciphertext.validateMIC(ciphertext.getRawCipherText());
			if ( success ) {
				throw new EncryptionException("Decryption failed", "Decryption problem: " + e.getMessage(), e);
			} else {
				throw new EncryptionException("Decryption failed",
						"Decryption problem: WARNING: Adversary may have tampered with " +
						"CipherText object orCipherText object mangled in transit: " + e.getMessage(), e);
			}
		}

	}

	/**
	* {@inheritDoc}
	*/
	public String sign(String data) throws EncryptionException {
		try {
			Signature signer = Signature.getInstance(signatureAlgorithm);
			signer.initSign(privateKey);
			signer.update(data.getBytes(encoding));
			byte[] bytes = signer.sign();
			return ESAPI.encoder().encodeForBase64(bytes, false);
		} catch (InvalidKeyException ike) {
			throw new EncryptionException("Encryption failure", "Must install unlimited strength crypto extension from Sun", ike);
		} catch (Exception e) {
			throw new EncryptionException("Signature failure", "Can't find signature algorithm " + signatureAlgorithm, e);
		}
	}
		
	/**
	* {@inheritDoc}
	*/
	public boolean verifySignature(String signature, String data) {
		try {
			byte[] bytes = ESAPI.encoder().decodeFromBase64(signature);
			Signature signer = Signature.getInstance(signatureAlgorithm);
			signer.initVerify(publicKey);
			signer.update(data.getBytes(encoding));
			return signer.verify(bytes);
		} catch (Exception e) {
			new EncryptionException("Invalid signature", "Problem verifying signature: " + e.getMessage(), e);
			return false;
		}
	}

	/**
	* {@inheritDoc}
     *
     * @param expiration
     * @throws IntegrityException
     */
	public String seal(String data, long expiration) throws IntegrityException {
		try {
			// mix in some random data so even identical data and timestamp produces different seals
			String random = ESAPI.randomizer().getRandomString(10, DefaultEncoder.CHAR_ALPHANUMERICS);
			String plaintext = expiration + ":" + random + ":" + data;
			// add integrity check
			String sig = this.sign( plaintext );
			String ciphertext = this.encrypt( plaintext + ":" + sig );
			return ciphertext;
		} catch( EncryptionException e ) {
			throw new IntegrityException( e.getUserMessage(), e.getLogMessage(), e );
		}
	}

	/**
	* {@inheritDoc}
	*/
	public String unseal(String seal) throws EncryptionException {
		String plaintext = null;
		try {
			System.out.println( "DECRYPTING: " + seal );
			plaintext = decrypt(seal);

			String[] parts = plaintext.split(":");
			if (parts.length != 4) {
				throw new EncryptionException("Invalid seal", "Seal was not formatted properly");
			}
	
			String timestring = parts[0];
			long now = new Date().getTime();
			long expiration = Long.parseLong(timestring);
			if (now > expiration) {
				throw new EncryptionException("Invalid seal", "Seal expiration date has expired");
			}
			String random = parts[1];
			String data = parts[2];
			String sig = parts[3];
			if (!this.verifySignature(sig, timestring + ":" + random + ":" + data ) ) {
				throw new EncryptionException("Invalid seal", "Seal integrity check failed");
			}	
			return data;
		} catch (EncryptionException e) {
			throw e;
		} catch (Exception e) {
			throw new EncryptionException("Invalid seal", "Invalid seal:" + e.getMessage(), e);
		}
	}

	
	/**
	* {@inheritDoc}
	*/
	public boolean verifySeal( String seal ) {
		try {
			unseal( seal );
			return true;
		} catch( Exception e ) {
			return false;
		}
	}
	
	/**
	* {@inheritDoc}
	*/
	public long getTimeStamp() {
		return new Date().getTime();
	}

	/**
	* {@inheritDoc}
	*/
	public long getRelativeTimeStamp( long offset ) {
		return new Date().getTime() + offset;
	}

   /**
    * Compute an HMAC for a String.  Experimental
    */
	/******
	private String computeHMAC( String input ) throws EncryptionException {
		try {
			Mac hmac = Mac.getInstance("HMacMD5"); // DISCUSS: Change to HMacSHA1. MD5 badly broken
												   //          SHA1 should really be avoided, but using
												   //		   for HMAC-SHA1 is acceptable for now. Plan
												   //		   to migrate to SHA-256 or NIST replacement for
												   //		   SHA1 in not to distant future.
			hmac.init(secretKeySpec);
			byte[] bytes = hmac.doFinal(input.getBytes());
			return ESAPI.encoder().encodeForBase64(bytes, false);
		} catch (InvalidKeyException ike) {
			throw new EncryptionException("Encryption failure", "Must install unlimited strength crypto extension from Sun", ike);
	    } catch (Exception e) {
	    	throw new EncryptionException("Could not compute HMAC", "Problem computing HMAC for " + input, e );
	    }
	}
	*****/
}